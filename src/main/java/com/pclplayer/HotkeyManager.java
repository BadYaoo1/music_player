package com.pclplayer;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import javafx.application.Platform;

import java.io.FileWriter;
import java.time.LocalTime;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 全局键盘/鼠标钩子（Windows WH_KEYBOARD_LL + WH_MOUSE_LL）。
 * 即使程序窗口未聚焦也能捕获按键/鼠标侧键/滚轮；支持录制按键组合与按绑定触发动作。
 * 非 Windows 平台 install() 直接返回，不会加载 JNA，也不做任何拦截。
 *
 * 注：当前 jna-platform 版本的 WinUser 未导出所需 VK_* 常量，故此处自行定义虚拟键码。
 */
public class HotkeyManager {

    public static final String PLAY_PAUSE = "PLAY_PAUSE";
    public static final String NEXT = "NEXT";
    public static final String PREV = "PREV";
    public static final String DESKTOP_LYRIC = "DESKTOP_LYRIC";

    // 虚拟键码（与 Windows SDK 一致）
    private static final int VK_CONTROL = 0x11;
    private static final int VK_MENU = 0x12;          // Alt
    private static final int VK_SHIFT = 0x10;
    private static final int VK_LWIN = 0x5B;
    private static final int VK_RWIN = 0x5C;
    private static final int VK_ESCAPE = 0x1B;
    private static final int VK_BACK = 0x08;
    private static final int VK_TAB = 0x09;
    private static final int VK_RETURN = 0x0D;
    private static final int VK_SPACE = 0x20;
    private static final int VK_PRIOR = 0x21;         // PageUp
    private static final int VK_NEXT = 0x22;          // PageDown
    private static final int VK_END = 0x23;
    private static final int VK_HOME = 0x24;
    private static final int VK_LEFT = 0x25;
    private static final int VK_UP = 0x26;
    private static final int VK_RIGHT = 0x27;
    private static final int VK_DOWN = 0x28;
    private static final int VK_INSERT = 0x2D;
    private static final int VK_DELETE = 0x2E;
    private static final int VK_XBUTTON1 = 0x05;       // 鼠标侧键（后退）
    private static final int VK_XBUTTON2 = 0x06;       // 鼠标侧键（前进）
    private static final int VK_VOLUME_MUTE = 0xAD;
    private static final int VK_VOLUME_DOWN = 0xAE;
    private static final int VK_VOLUME_UP = 0xAF;
    private static final int VK_MEDIA_NEXT_TRACK = 0xB0;
    private static final int VK_MEDIA_PREV_TRACK = 0xB1;
    private static final int VK_MEDIA_PLAY_PAUSE = 0xB3;
    private static final int VK_WHEEL_UP = 0xC0;       // 滚轮上（自定义）
    private static final int VK_WHEEL_DOWN = 0xC1;     // 滚轮下（自定义）

    // 动作 -> 组合键字符串（如 "Ctrl+Alt+Space"），空串表示未绑定
    private final Map<String, String> bindings = new ConcurrentHashMap<>();

    private WinUser.HHOOK hook;
    private WinUser.HHOOK mouseHook;
    private LowLevelKeyboardProc proc;
    private LowLevelMouseProc mouseProc;
    private Thread hookThread;
    private volatile boolean running = false;

    private volatile String recordingAction = null;
    private volatile Consumer<String> recordingCallback = null;
    private Consumer<String> actionHandler;

    // 文本输入框聚焦时不为 true，避免拦截正常输入
    private volatile boolean textInputFocused = false;

    // 跟踪当前按下的物理键，过滤系统自动重复
    private final Set<Integer> downKeys = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static volatile int logCount = 0;
    private static volatile int diagCount = 0;

    public void setActionHandler(Consumer<String> h) { this.actionHandler = h; }
    public void setBinding(String action, String combo) { bindings.put(action, combo == null ? "" : combo); }
    public String getBinding(String action) { return bindings.getOrDefault(action, ""); }
    public boolean isRecording() { return recordingAction != null; }
    public String getRecordingAction() { return recordingAction; }
    public void startRecording(String action, Consumer<String> cb) { recordingAction = action; recordingCallback = cb; }
    public void cancelRecording() { recordingAction = null; recordingCallback = null; }
    public void setTextInputFocused(boolean b) { this.textInputFocused = b; }

    /** 安装全局钩子（仅 Windows）。键盘钩子 + 鼠标钩子挂在同一线程。重复调用安全。 */
    public void install() {
        if (!isWindows()) return;
        if (running) return;
        running = true;
        proc = new LowLevelKeyboardProc();
        mouseProc = new LowLevelMouseProc();
        hookThread = new Thread(() -> {
            // WH_KEYBOARD_LL / WH_MOUSE_LL 的钩子过程位于当前进程内，hMod 必须为 NULL
            hook = User32.INSTANCE.SetWindowsHookEx(13, proc, null, 0);
            if (hook == null) {
                log("SetWindowsHookEx(KEYBOARD) FAILED, GetLastError=" + Kernel32.INSTANCE.GetLastError());
                running = false;
                return;
            }
            log("SetWindowsHookEx(KEYBOARD) OK");
            // 鼠标钩子失败不致命，仍保留键盘快捷键功能
            mouseHook = User32.INSTANCE.SetWindowsHookEx(14, mouseProc, null, 0);
            if (mouseHook == null) {
                log("SetWindowsHookEx(MOUSE) FAILED, GetLastError=" + Kernel32.INSTANCE.GetLastError());
            } else {
                log("SetWindowsHookEx(MOUSE) OK");
            }
            WinUser.MSG msg = new WinUser.MSG();
            while (running && User32.INSTANCE.GetMessage(msg, null, 0, 0) != 0) {
                User32.INSTANCE.TranslateMessage(msg);
                User32.INSTANCE.DispatchMessage(msg);
            }
            log("GetMessage loop exited, running=" + running);
        }, "GlobalHotkeyHook");
        hookThread.setDaemon(true);
        hookThread.start();
    }

    /** 卸载全局钩子并终止消息循环 */
    public void uninstall() {
        running = false;
        if (hook != null) {
            User32.INSTANCE.UnhookWindowsHookEx(hook);
            hook = null;
        }
        if (mouseHook != null) {
            User32.INSTANCE.UnhookWindowsHookEx(mouseHook);
            mouseHook = null;
        }
        cancelRecording();
        if (hookThread != null) {
            try {
                User32.INSTANCE.PostThreadMessage((int) hookThread.getId(), 0x12,
                        new WinDef.WPARAM(0), new WinDef.LPARAM(0));
            } catch (Throwable ignore) {}
            try { hookThread.join(500); } catch (InterruptedException ignore) {}
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static void log(String s) {
        try (FileWriter fw = new FileWriter("hotkey_debug.log", true)) {
            fw.write(LocalTime.now() + " " + s + "\n");
        } catch (Throwable ignore) {}
    }

    private String matchAction(String combo) {
        for (String a : bindings.keySet()) {
            String b = bindings.get(a);
            if (b != null && !b.isEmpty() && b.equals(combo)) return a;
        }
        return null;
    }

    private String buildCombo(boolean ctrl, boolean alt, boolean shift, boolean win, int vk) {
        StringBuilder sb = new StringBuilder();
        if (ctrl) sb.append("Ctrl+");
        if (alt) sb.append("Alt+");
        if (shift) sb.append("Shift+");
        if (win) sb.append("Win+");
        sb.append(vkToName(vk));
        return sb.toString();
    }

    private static boolean isModifierKey(int vk) {
        return vk == VK_CONTROL || vk == VK_MENU
                || vk == VK_SHIFT || vk == VK_LWIN || vk == VK_RWIN;
    }

    // 自维护修饰键按下状态：LL 钩子能看见每一个按键事件，比在回调里调 GetAsyncKeyState 更可靠
    // （LL 键盘钩子的 keydown 早于系统"提交"该键，回调内读 GetAsyncKeyState 常拿到按键前的旧状态）
    private volatile boolean mCtrl = false, mAlt = false, mShift = false, mWinL = false, mWinR = false;

    private void updateMod(int vk, boolean pressed) {
        switch (vk) {
            case VK_CONTROL -> mCtrl = pressed;
            case VK_MENU    -> mAlt = pressed;
            case VK_SHIFT   -> mShift = pressed;
            case VK_LWIN    -> mWinL = pressed;
            case VK_RWIN    -> mWinR = pressed;
        }
    }

    /** 读取当前修饰键状态：以自维护状态为主，GetAsyncKeyState 兜底（兼顾程序启动前已按下的键）。 */
    private boolean[] readModifiers() {
        boolean ctrl = mCtrl || (User32.INSTANCE.GetAsyncKeyState(VK_CONTROL) & 0x8000) != 0;
        boolean alt = mAlt || (User32.INSTANCE.GetAsyncKeyState(VK_MENU) & 0x8000) != 0;
        boolean shift = mShift || (User32.INSTANCE.GetAsyncKeyState(VK_SHIFT) & 0x8000) != 0;
        boolean win = mWinL || mWinR
                || (User32.INSTANCE.GetAsyncKeyState(VK_LWIN) & 0x8000) != 0
                || (User32.INSTANCE.GetAsyncKeyState(VK_RWIN) & 0x8000) != 0;
        return new boolean[]{ctrl, alt, shift, win};
    }

    /** 处理一次"主键"事件：录制或触发对应组合键动作。返回 true 表示应吞掉该事件。 */
    private boolean handlePrimaryKey(int vk) {
        boolean[] m = readModifiers();
        if (recordingAction != null) {
            String combo = buildCombo(m[0], m[1], m[2], m[3], vk);
            if (diagCount < 60) { diagCount++; log("RECORD " + recordingAction + " -> " + combo); }
            Consumer<String> cb = recordingCallback;
            cancelRecording();
            if (cb != null) Platform.runLater(() -> cb.accept(combo));
            return true;
        }
        if (downKeys.add(vk)) {
            String combo = buildCombo(m[0], m[1], m[2], m[3], vk);
            String action = matchAction(combo);
            if (action != null && actionHandler != null) {
                final String act = action;
                if (diagCount < 60) { diagCount++; log("TRIGGER " + act + " via " + combo); }
                Platform.runLater(() -> actionHandler.accept(act));
                return true;
            }
        }
        return false;
    }

    private static String vkToName(int vk) {
        return switch (vk) {
            case VK_CONTROL -> "Ctrl";
            case VK_MENU -> "Alt";
            case VK_SHIFT -> "Shift";
            case VK_LWIN, VK_RWIN -> "Win";
            case VK_BACK -> "Backspace";
            case VK_TAB -> "Tab";
            case VK_RETURN -> "Enter";
            case VK_ESCAPE -> "Esc";
            case VK_SPACE -> "Space";
            case VK_PRIOR -> "PgUp";
            case VK_NEXT -> "PgDn";
            case VK_END -> "End";
            case VK_HOME -> "Home";
            case VK_LEFT -> "Left";
            case VK_UP -> "Up";
            case VK_RIGHT -> "Right";
            case VK_DOWN -> "Down";
            case VK_INSERT -> "Ins";
            case VK_DELETE -> "Del";
            case VK_XBUTTON1 -> "X1";
            case VK_XBUTTON2 -> "X2";
            case VK_WHEEL_UP -> "WheelUp";
            case VK_WHEEL_DOWN -> "WheelDown";
            case VK_VOLUME_MUTE -> "Mute";
            case VK_VOLUME_DOWN -> "VolDown";
            case VK_VOLUME_UP -> "VolUp";
            case VK_MEDIA_NEXT_TRACK -> "MediaNext";
            case VK_MEDIA_PREV_TRACK -> "MediaPrev";
            case VK_MEDIA_PLAY_PAUSE -> "MediaPlayPause";
            default -> {
                if (vk >= 0x30 && vk <= 0x39) yield String.valueOf((char) vk);            // 0-9
                if (vk >= 0x41 && vk <= 0x5A) yield String.valueOf((char) vk);            // A-Z
                if (vk >= 0x60 && vk <= 0x69) yield "Num" + (vk - 0x60);                  // 小键盘 0-9
                if (vk >= 0x70 && vk <= 0x7B) yield "F" + (vk - 0x6F);                     // F1-F12
                yield "0x" + Integer.toHexString(vk).toUpperCase();
            }
        };
    }

    /** 低级键盘钩子回调（运行在钩子线程上）。实现 WinUser.HOOKPROC（空标记接口，extends StdCallCallback）。 */
    private class LowLevelKeyboardProc implements WinUser.HOOKPROC {
        public WinUser.LRESULT callback(int nCode, WinDef.WPARAM wParam, WinDef.LPARAM lParam) {
            try {
                if (nCode >= 0) {
                    int msg = wParam.intValue();
                    boolean down = (msg == 0x0100 || msg == 0x0104);
                    boolean up = (msg == 0x0101 || msg == 0x0105);
                    Pointer p = new Pointer(lParam.longValue());
                    int vk = p.getInt(0);
                    // LL 键盘钩子对修饰键返回左右区分的 vkCode（0xA0-0xA5），统一成中性码便于识别
                    switch (vk) {
                        case 0xA0, 0xA1 -> vk = VK_SHIFT;
                        case 0xA2, 0xA3 -> vk = VK_CONTROL;
                        case 0xA4, 0xA5 -> vk = VK_MENU;
                    }
                    if (logCount < 30) { logCount++; log("kb nCode=" + nCode + " msg=0x" + Integer.toHexString(msg) + " vk=0x" + Integer.toHexString(vk)); }
                    if (down) {
                        if (isModifierKey(vk)) {
                            updateMod(vk, true);
                            return User32.INSTANCE.CallNextHookEx(hook, nCode, wParam, lParam);
                        }
                        if (textInputFocused) return User32.INSTANCE.CallNextHookEx(hook, nCode, wParam, lParam);
                        if (vk == VK_ESCAPE && recordingAction != null) {
                            // 录制状态下按 Esc：清空绑定（combo=null）
                            Consumer<String> cb = recordingCallback;
                            cancelRecording();
                            if (cb != null) Platform.runLater(() -> cb.accept(null));
                            return new WinUser.LRESULT(1);
                        }
                        if (handlePrimaryKey(vk)) return new WinUser.LRESULT(1);
                    } else if (up) {
                        if (isModifierKey(vk)) updateMod(vk, false);
                        else downKeys.remove(vk);
                    }
                }
            } catch (Throwable t) {
                // 绝不让异常冒泡回原生线程
            }
            return User32.INSTANCE.CallNextHookEx(hook, nCode, wParam, lParam);
        }
    }

    /** 低级鼠标钩子回调（运行在钩子线程上）。处理侧键与滚轮；左/右/中键不参与录制/触发。 */
    private class LowLevelMouseProc implements WinUser.HOOKPROC {
        public WinUser.LRESULT callback(int nCode, WinDef.WPARAM wParam, WinDef.LPARAM lParam) {
            try {
                if (nCode >= 0) {
                    int msg = wParam.intValue();
                    int vk = 0;
                    boolean down = false;
                    boolean up = false;
                    if (msg == 0x020B) {           // WM_XBUTTONDOWN
                        vk = xButtonVk(lParam); down = true;
                    } else if (msg == 0x020C) {    // WM_XBUTTONUP
                        vk = xButtonVk(lParam); up = true;
                    } else if (msg == 0x020A) {     // WM_MOUSEWHEEL
                        vk = wheelVk(lParam); down = true;
                    }
                    if (vk != 0) {
                        if (down) {
                            if (handlePrimaryKey(vk)) return new WinUser.LRESULT(1);
                        } else if (up) {
                            downKeys.remove(vk);
                        }
                    }
                }
            } catch (Throwable t) {
                // 绝不让异常冒泡回原生线程
            }
            return User32.INSTANCE.CallNextHookEx(mouseHook, nCode, wParam, lParam);
        }

        private int xButtonVk(WinDef.LPARAM lParam) {
            // MSLLHOOKSTRUCT.mouseData 在偏移 8，高 16 位为 XBUTTON1(1)/XBUTTON2(2)
            int md = new Pointer(lParam.longValue()).getInt(8);
            int xbtn = (md >> 16) & 0xFFFF;
            return xbtn == 1 ? VK_XBUTTON1 : (xbtn == 2 ? VK_XBUTTON2 : 0);
        }

        private int wheelVk(WinDef.LPARAM lParam) {
            // MSLLHOOKSTRUCT.mouseData 在偏移 8，高 16 位为有符号滚轮增量（>0 上，<0 下）
            int md = new Pointer(lParam.longValue()).getInt(8);
            int delta = (short) ((md >> 16) & 0xFFFF);
            return delta > 0 ? VK_WHEEL_UP : VK_WHEEL_DOWN;
        }
    }
}
