import { useEffect, useRef } from 'react';
import {
    setAudioSrc,
    setLyricsState,
    setCurrentSong,
    setPlayerState,
    setCurrentLineIndex,
    setPlayQueue,
    setActivePlaybackContext,
    setTransitionDisplay,
    setCachedCoverUrl,
    setDuration,
    usePlaybackStore,
} from '../stores/usePlaybackStore';
import { useAppViewStore } from '../stores/useAppViewStore';

const HOST_STATE_URL = '/api/state';
const HOST_EVENT_URL = '/api/host-events';
const POLL_INTERVAL_MS = 1000;

interface HostState {
    audioSrc?: string | null;
    title?: string;
    artist?: string;
    album?: string | null;
    coverUrl?: string | null;
    durationMs?: number;
    lyrics?: any | null;
    lyricsB64?: string | null;
    songId?: string;
    playQueue?: any[] | null;
    switching?: boolean;
    lyricOffsetMs?: number;
}

let _eventSeq = 0;
function nextEventId(): string {
    return 'hse_' + (++_eventSeq) + '_' + Date.now();
}

/** 立刻静音 deck（切歌时用：不等 Java 下载完，旧歌马上停）。 */
function stopDeckNow() {
    try {
        const el = document.querySelector('audio[data-playback-deck]') as HTMLAudioElement | null;
        if (el) el.pause();
    } catch {}
}

/**
 * 宿主模式（?host=1）的极简同步：
 *   - Java → Folia：只同步曲目（audioSrc/songId）与歌词（含歌词偏移）、歌单。
 *     播放/暂停/进度完全不参与同步。
 *   - Folia → Java：只在两处上报 —— 用户切歌（songChange）、关闭浏览器（close）。
 *     关闭上报用 sendBeacon，带最终 timeSec 与 playing，供主播放器恢复进度。
 */
export function useHostSync() {
    const forcedPlayerView = useRef(false);
    const lastAudioSrc = useRef<string | null | undefined>(undefined);
    const lastSongId = useRef<string | undefined>(undefined);
    const lastLyricsB64 = useRef<string | undefined>(undefined);
    const lastPlayQueueSig = useRef<string | undefined>(undefined);
    const lastLyricOffset = useRef<number | undefined>(undefined);
    const lastJavaSongId = useRef<string | undefined>(undefined);  // 上次 Java poll 返回的 songId

    // Folia 自己切歌后，等 Java 下载转码期间阻止 Folia 播放新歌
    const _awaitingJavaSong = useRef<string | null>(null);
    // 主播放器（Java）正在切歌：立即静音旧歌，等新 audioSrc 就绪
    const _javaSwitching = useRef(false);

    const hostMode = typeof window !== 'undefined'
        && new URLSearchParams(window.location.search).get('host') === '1';

    const postEvent = async (evt: any) => {
        try {
            await fetch(HOST_EVENT_URL, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(evt),
            });
        } catch {}
    };

    // ------- 关闭浏览器时上报最终进度（sendBeacon 在页面卸载后仍会发出）-------
    useEffect(() => {
        if (!hostMode) return;

        const report = () => {
            try {
                const el = document.querySelector('audio[data-playback-deck]') as HTMLAudioElement | null;
                const st = usePlaybackStore.getState();
                const payload = JSON.stringify({
                    type: 'close',
                    id: nextEventId(),
                    timeSec: el ? el.currentTime : 0,
                    playing: st.playerState === 'PLAYING',
                });
                if (navigator.sendBeacon) {
                    navigator.sendBeacon(HOST_EVENT_URL, new Blob([payload], { type: 'application/json' }));
                } else {
                    fetch(HOST_EVENT_URL, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: payload,
                        keepalive: true,
                    }).catch(() => {});
                }
                console.log('[HostSync] close reported:', payload);
            } catch {}
        };

        window.addEventListener('pagehide', report);
        window.addEventListener('beforeunload', report);
        return () => {
            window.removeEventListener('pagehide', report);
            window.removeEventListener('beforeunload', report);
        };
    }, [hostMode]);

    // ------- 宿主模式：启动后自动进入播放页 -------
    // 启动早期应用初始化 / 首个曲目到达前可能把视图重置回首页，这里在前几秒内
    // 持续纠正；之后不再干预，用户仍可自由切到首页等页面。
    useEffect(() => {
        if (!hostMode || forcedPlayerView.current) return;
        forcedPlayerView.current = true;
        const enterPlayer = () => {
            try {
                const store = useAppViewStore.getState();
                if (store.view !== 'player') store.setView('player');
            } catch {}
        };
        enterPlayer();
        const timers = [300, 800, 1500, 2500, 3500].map(ms => window.setTimeout(enterPlayer, ms));
        return () => timers.forEach(t => window.clearTimeout(t));
    }, [hostMode]);

    // ------- Poll: Java → Folia（曲目 / 歌词 / 歌单 / 歌词偏移）-------
    useEffect(() => {
        if (!hostMode) return;

        let cancelled = false;

        const tick = async () => {
            if (cancelled) return;
            try {
                const st = usePlaybackStore.getState();
                const csNow = st.currentSong as any;

                // ★ 检测 Folia 自己切歌：currentSong.id 变了 = 用户切歌了
                const foliaSongIdNow: string | undefined = csNow?.id ?? csNow?.bvid;
                if (foliaSongIdNow
                    && lastJavaSongId.current !== undefined
                    && foliaSongIdNow !== lastJavaSongId.current
                    && !_awaitingJavaSong.current) {
                    const bvid = foliaSongIdNow;
                    console.log('[HostSync] Folia songChange detected! foliaId=', bvid, 'javaId=', lastJavaSongId.current);
                    _awaitingJavaSong.current = bvid;
                    // 立刻停掉旧歌，等 Java 下载好再切
                    stopDeckNow();
                    try { setAudioSrc(''); } catch {}
                    try { setPlayerState('PLAYING'); } catch {}
                    await postEvent({ type: 'songChange', id: nextEventId(), songId: bvid });
                    // 注意：不要重置 lastAudioSrc！否则下一轮 poll 拿到 Java 的旧 audioSrc
                    // 会被当成"新地址"重新应用 → 旧歌又播几秒（回弹）
                }

                const resp = await fetch(HOST_STATE_URL, { cache: 'no-store' });
                if (!resp.ok) { scheduleNext(); return; }
                const s: HostState = await resp.json();
                if (cancelled) return;

                // Java 正在切歌（新曲目已选定、文件还没就绪）→ 立刻静音旧歌，等新 audioSrc
                if (s.switching && !_javaSwitching.current) {
                    _javaSwitching.current = true;
                    console.log('[HostSync] Java switching, stop deck');
                    stopDeckNow();
                    try { setAudioSrc(''); } catch {}
                    try { setPlayerState('PLAYING'); } catch {}
                } else if (!s.switching && _javaSwitching.current) {
                    _javaSwitching.current = false;
                }

                // ============ 曲目：只在首次加载 / 等 Java 下载 / Java 换了歌时应用 ============
                const awaitingJava = !!_awaitingJavaSong.current;
                const foliaAudioSrc = st.audioSrc as string | undefined;
                const foliaHasAudio = !!foliaAudioSrc && foliaAudioSrc.startsWith?.('http');
                const javaAudioMatchesFolia = !!foliaAudioSrc && !!s.audioSrc && s.audioSrc === foliaAudioSrc;

                if (s.audioSrc != null && s.audioSrc !== lastAudioSrc.current) {
                    if (awaitingJava || !foliaHasAudio || !javaAudioMatchesFolia) {
                        console.log('[HostSync] apply audioSrc:', s.audioSrc, awaitingJava ? '(awaiting)' : foliaHasAudio ? '(different)' : '(init)');
                        setTransitionDisplay(null);
                        setActivePlaybackContext('local');
                        setAudioSrc(s.audioSrc);
                        lastAudioSrc.current = s.audioSrc;
                        _awaitingJavaSong.current = null;  // Java 已就绪
                        // 新音频挂上后：从头播 + 主动触发播放（切歌时旧播放意图可能已丢失）
                        (() => {
                            let tries = 0;
                            const timer = setInterval(() => {
                                tries++;
                                const el = document.querySelector('audio[data-playback-deck]') as HTMLAudioElement | null;
                                if (el && el.getAttribute('src')) {
                                    clearInterval(timer);
                                    try { el.currentTime = 0; } catch {}
                                    el.play().catch(() => {});
                                } else if (tries >= 40) {
                                    clearInterval(timer);
                                }
                            }, 250);
                        })();
                    } else {
                        lastAudioSrc.current = s.audioSrc;
                    }
                }

                if (s.songId && s.songId !== lastSongId.current) {
                    if (awaitingJava || !foliaHasAudio || !javaAudioMatchesFolia) {
                        lastSongId.current = s.songId;
                        const song: any = {
                            id: s.songId,
                            name: s.title ?? '',
                            artists: [{ id: s.artist ?? '', name: s.artist ?? '' }],
                            album: { id: '', name: '', coverUrl: s.coverUrl ?? null },
                            durationMs: s.durationMs ?? 0,
                        };
                        setCurrentSong(song);
                        setDuration((s.durationMs ?? 0) / 1000);
                        if (s.coverUrl) setCachedCoverUrl(s.coverUrl);
                    } else {
                        lastSongId.current = s.songId;
                    }
                }

                // ★ 更新基准：下次 tick 开头对比 Folia store 的 currentSong.id 用这个
                lastJavaSongId.current = s.songId;

                // 同一首歌的元数据后补（bvid 占位 → 真实歌名、空作者 → UP 主）也要刷新。
                // 否则 Java 后台补齐后 songId 没变，Folia 会一直停在 bvid / ui.unknownArtist。
                // 只填空位，绝不用占位值覆盖已有的真实歌名/作者。
                if (s.songId) {
                    const cur: any = usePlaybackStore.getState().currentSong;
                    if (cur && cur.id === s.songId) {
                        const newName = s.title ?? '';
                        const newArtist = s.artist ?? '';
                        const curArtist = cur.artists?.[0]?.name ?? '';
                        const nameIsPlaceholder = !cur.name || cur.name === cur.id || cur.name === s.songId;
                        const artistIsPlaceholder = !curArtist || curArtist === '未知歌手'
                            || curArtist === '未知艺术家' || curArtist === 'ui.unknownArtist';
                        const betterName = !!newName && newName !== s.songId;
                        const betterArtist = !!newArtist && newArtist !== '未知歌手' && newArtist !== 'ui.unknownArtist';
                        if ((nameIsPlaceholder && betterName) || (artistIsPlaceholder && betterArtist)) {
                            const name = (nameIsPlaceholder && betterName) ? newName : cur.name;
                            const artist = (artistIsPlaceholder && betterArtist) ? newArtist : curArtist;
                            setCurrentSong({ ...cur, name, artists: [{ id: artist, name: artist }] });
                        }
                    }
                }

                // lyrics (Base64 → UTF-8 → JSON)
                // 直接比对整段歌词内容：主程序切换歌词来源 / 重新匹配 / 清空后，内容一变就刷新。
                // 旧实现只看行数 + 首行，遇到不同来源行数相同、首行相同的情况会一直不更新。
                const lyricsB64 = s.lyricsB64 ?? '';
                if (lyricsB64 && lyricsB64 !== lastLyricsB64.current) {
                    lastLyricsB64.current = lyricsB64;
                    try {
                        const bin = atob(lyricsB64);
                        const bytes = new Uint8Array(bin.length);
                        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                        const parsed = JSON.parse(new TextDecoder('utf-8').decode(bytes));
                        const rawLines: any[] = Array.isArray(parsed) ? parsed : [];
                        const foliaLines = rawLines.map((l: any) => {
                            const st = (l.start ?? 0) / 1000;
                            const et = Math.max((l.end ?? 0) / 1000, st + 0.5);
                            const fullText = l.text ?? '';
                            return {
                                startTime: st, endTime: et, fullText,
                                words: [{ text: fullText, startTime: st, endTime: et }],
                                renderHints: {},
                            };
                        });
                        setLyricsState({ lines: foliaLines } as any);
                        setCurrentLineIndex(0);
                    } catch {}
                }

                // playQueue
                if (Array.isArray(s.playQueue) && s.playQueue.length > 0) {
                    // 签名包含歌名/作者：Java 后台补齐元数据后能触发重新注入（否则队列一直显示 bvid）
                    const sig = JSON.stringify(s.playQueue.map((q: any) =>
                        [q.id ?? q.bvid, q.name ?? q.title ?? '', q.artists?.[0]?.name ?? q.artist ?? '']));
                    if (sig !== lastPlayQueueSig.current) {
                        lastPlayQueueSig.current = sig;
                        const foliaQueue = s.playQueue.map((q: any) => {
                            const name = q.name || q.title || q.id || q.bvid || '';
                            let artists = q.artists;
                            if (!artists && q.artist) artists = [{ id: q.artist, name: q.artist }];
                            if (!artists || artists.length === 0) artists = [];
                            let album = q.album ?? { id: '', name: q.albumName ?? '', coverUrl: q.coverUrl ?? null };
                            return { id: q.id ?? q.bvid ?? '', name, artists, album, durationMs: q.durationMs ?? 0 };
                        });
                        setPlayQueue(foliaQueue as any);
                    }
                }

                // lyric offset（唯一需要从 Java 同步过来的歌词状态）
                if (typeof s.lyricOffsetMs === 'number' && s.lyricOffsetMs !== lastLyricOffset.current) {
                    lastLyricOffset.current = s.lyricOffsetMs;
                    try { usePlaybackStore.getState().setLyricTimelineOffsetMs?.(s.lyricOffsetMs); } catch {}
                }
            } catch {}
            scheduleNext();
        };

        const scheduleNext = () => { setTimeout(tick, POLL_INTERVAL_MS); };
        const bootTimer = setTimeout(tick, 800);
        return () => { cancelled = true; clearTimeout(bootTimer); };
    }, [hostMode]);

    return null;
}
