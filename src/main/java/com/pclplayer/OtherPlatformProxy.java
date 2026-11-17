package com.pclplayer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.*;

/**
 * 跨平台歌词兜底 —— 通过 Node.js 子进程调用 lyric-bridge.js
 */
public class OtherPlatformProxy {

    static final String NODE_BIN = System.getProperty("os.name").toLowerCase().contains("win")
            ? "node.exe" : "node";
    static final Path BRIDGE_JS = Paths.get(System.getProperty("user.dir"), "lyric-bridge.js");

    /**
     * 定位 Node 可执行文件：优先用随程序一起打包的那份（安装目录下的 node.exe），
     * 找不到再退回系统 PATH 里的 node。
     * 这样绿色包发到没装 Node 的电脑上，QQ / 酷狗歌词依然能取到。
     */
    static String resolveNodeCmd() {
        for (Path p : nodeCandidates()) {
            if (Files.isRegularFile(p)) {
                System.out.println("  ℹ [Node.js] 使用随包运行时: " + p.toAbsolutePath());
                return p.toAbsolutePath().toString();
            }
        }
        return NODE_BIN;   // 退回系统 PATH
    }

    /** node.exe 的候选位置：exe 同级 / lib 同级 / 上两级 / 工作目录 / 开发期 nodetest */
    private static java.util.List<Path> nodeCandidates() {
        java.util.List<Path> list = new java.util.ArrayList<>();
        // 打包后：app/PCPlayerUI/node.exe（jar 在 app/PCPlayerUI/lib/ 下）
        try {
            Path self = Paths.get(OtherPlatformProxy.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            // 从 jar 所在目录逐级向上找。打包后 jar 在 app/<name>/lib/ 下，
            // 而随包 node.exe 在安装根目录，需要向上 3 级；多遍历几层以兼容不同布局。
            Path dir = Files.isDirectory(self) ? self : self.getParent();
            for (int i = 0; dir != null && i < 5; i++) {
                list.add(dir.resolve(NODE_BIN));
                dir = dir.getParent();
            }
        } catch (Exception ignore) { }
        // 工作目录（jpackage 快捷方式把 StartIn 设为安装目录）
        Path cwd = Paths.get(System.getProperty("user.dir"));
        list.add(cwd.resolve(NODE_BIN));
        list.add(cwd.resolve("nodetest").resolve(NODE_BIN));   // 开发态
        return list;
    }

    /** 定位 lyric-bridge.js：工作目录优先，其次程序所在目录 */
    static Path resolveBridgeJs() {
        if (Files.isRegularFile(BRIDGE_JS)) return BRIDGE_JS;
        try {
            Path self = Paths.get(OtherPlatformProxy.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            // 同 node.exe：jar 在 app/<name>/lib/ 下，脚本在安装根目录，需向上多级查找
            Path dir = Files.isDirectory(self) ? self : self.getParent();
            for (int i = 0; dir != null && i < 5; i++) {
                Path js = dir.resolve("lyric-bridge.js");
                if (Files.isRegularFile(js)) return js;
                dir = dir.getParent();
            }
        } catch (Exception ignore) { }
        return BRIDGE_JS;
    }

    public static String fetchLyric(String title) {
        Path bridge = resolveBridgeJs();
        if (!Files.exists(bridge)) {
            System.out.println("  ⚠ lyric-bridge.js 不存在");
            return null;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    resolveNodeCmd(), bridge.toAbsolutePath().toString(), title);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            proc.waitFor(12, java.util.concurrent.TimeUnit.SECONDS);
            proc.destroyForcibly();

            String output = sb.toString().trim();
            if (output.isEmpty() || output.startsWith("ERROR")) return null;

            // 去掉第一行 SOURCE:qq
            int idx = output.indexOf('\n');
            String lrc = idx > 0 ? output.substring(idx + 1) : output;

            System.out.println("  ✅ [Node.js] 获取到歌词");
            return lrc;
        } catch (Exception e) {
            System.out.println("  ⚠ Node.js 调用异常: " + e.getMessage());
        }
        return null;
    }

    public static void main(String[] args) {
        if (args.length == 0) { System.out.println("用法: java OtherPlatformProxy <歌名>"); return; }
        String lrc = fetchLyric(String.join(" ", args));
        if (lrc != null) System.out.println("\n✅ 歌词:\n" + lrc);
        else System.out.println("\n❌ 未获取到");
    }
}
