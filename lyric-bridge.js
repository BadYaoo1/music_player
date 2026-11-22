#!/usr/bin/env node
const https = require('https');
const http = require('http');

const title = process.argv[2];
if (!title) { process.stdout.write('ERROR:no title\n'); process.exit(1); }

const MOBILE_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15';
const QQ_REFERER = 'https://y.qq.com/portal/player.html';
const KUGOU_REFERER = 'https://www.kugou.com/';
// 不再硬编码 QQ Cookie（会随脚本分发泄露）。需要登录态时用环境变量 QQ_COOKIE 传入即可。
const QQ_COOKIE = process.env.QQ_COOKIE || '';

/** QQ 音乐请求头：仅在配置了 Cookie 时才带上，避免发送空 Cookie 头 */
function qqHeaders() {
    const h = { 'Referer': QQ_REFERER };
    if (QQ_COOKIE) h['Cookie'] = QQ_COOKIE;
    return h;
}

function fetchUrl(url, customHeaders = {}) {
    return new Promise((resolve, reject) => {
        const mod = url.startsWith('https') ? https : http;
        const req = mod.get(url, {
            headers: {
                'User-Agent': MOBILE_UA,
                'Accept': 'application/json, text/plain, */*',
                'Accept-Language': 'zh-CN,zh;q=0.9',
                ...customHeaders,
            }
        }, (res) => {
            // 校验状态码：4xx/5xx 直接 reject，避免把错误页当歌词解析（静默失败难调试）
            const code = res.statusCode || 0;
            if (code < 200 || code >= 300) {
                res.resume();
                reject(new Error('HTTP ' + code));
                return;
            }
            let data = '';
            res.on('data', c => data += c);
            res.on('end', () => resolve(data));
        });
        req.on('error', reject);
        req.setTimeout(8000, () => { req.destroy(); reject(new Error('timeout')); });
    });
}

function cleanKeyword(keyword) {
    let m = keyword.match(/《([^》]+)》/);
    if (m) return m[1].trim();
    m = keyword.match(/「([^」]+)」/);
    if (m) return m[1].trim();
    // "歌名 - 歌手" 或 "歌手 - 歌名"：优先取较长的一段作歌名。
    // 之前取较短段会把 "JVKE - golden hour 正式版" 误判为 "JVKE"（歌手名），搜词失败。
    if (keyword.includes(' - ')) {
        const parts = keyword.split(' - ').map(s => s.trim()).filter(Boolean);
        if (parts.length >= 2) {
            parts.sort((a, b) => b.length - a.length);
            return parts[0];
        }
    }
    return keyword.replace(/\[.*?\]/g, '').replace(/【.*?】/g, '').replace(/（.*?）/g, '').replace(/\(.*?\)/g, '').trim();
}

async function fetchQQ() {
    try {
        const kw = cleanKeyword(title);
        const enc = encodeURIComponent(kw);
        const searchData = await fetchUrl(
            `https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${enc}&format=json&n=5&p=1`,
            { 'Referer': QQ_REFERER }
        );
        const search = JSON.parse(searchData);
        const list = search.data?.song?.list || [];
        // 逐条尝试：第一条没歌词时回退到后续结果，提高命中率
        for (const song of list) {
            const mid = song.songmid;
            if (!mid) continue;
            try {
                const lyricData = await fetchUrl(
                    `https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?nobase64=1&songmid=${mid}`,
                    qqHeaders()
                );
                const lyric = JSON.parse(lyricData);
                if (lyric.retcode && lyric.retcode !== 0) continue;
                if (lyric.lyric) return { lyric: lyric.lyric, source: 'qq' };
            } catch (e) {}
        }
    } catch (e) {}
    return null;
}

// 多候选：取搜索前 limit 首，每首歌词作为一个候选返回
async function fetchQQMulti(limit) {
    const out = [];
    try {
        const kw = cleanKeyword(title);
        const enc = encodeURIComponent(kw);
        const searchData = await fetchUrl(
            `https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${enc}&format=json&n=${limit}&p=1`,
            { 'Referer': QQ_REFERER }
        );
        const search = JSON.parse(searchData);
        const list = search.data?.song?.list || [];
        for (let i = 0; i < Math.min(limit, list.length); i++) {
            const song = list[i];
            const mid = song.songmid;
            if (!mid) continue;
            try {
                const lyricData = await fetchUrl(
                    `https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?nobase64=1&songmid=${mid}`,
                    qqHeaders()
                );
                const lyric = JSON.parse(lyricData);
                if (lyric.retcode && lyric.retcode !== 0) continue;
                if (lyric.lyric) {
                    const singer = (song.singer && song.singer[0] && song.singer[0].name) || '';
                    const name = song.songname || song.name || '未知';
                    out.push({ lyric: lyric.lyric, source: 'qq', title: name + (singer ? ' - ' + singer : '') });
                }
            } catch (e) {}
        }
    } catch (e) {}
    return out;
}

async function fetchKugou() {
    try {
        const kw = cleanKeyword(title);
        const enc = encodeURIComponent(kw);
        const searchData = await fetchUrl(
            `https://songsearch.kugou.com/song_search_v2?keyword=${enc}&page=1&pagesize=1&platform=WebFilter&format=json`,
            { 'Referer': KUGOU_REFERER }
        );
        const search = JSON.parse(searchData);
        const hash = search.data?.lists?.[0]?.FileHash;
        if (!hash) return null;
        const lyricData = await fetchUrl(
            `https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&hash=${hash}`,
            { 'Referer': KUGOU_REFERER }
        );
        const lyric = JSON.parse(lyricData);
        const candidate = lyric.candidates?.[0];
        if (!candidate) return null;
        const lrcData = await fetchUrl(
            `https://lyrics.kugou.com/download?ver=1&client=pc&id=${candidate.id}&accesskey=${candidate.accesskey}&fmt=lrc&charset=utf8`,
            { 'Referer': KUGOU_REFERER }
        );
        const lrc = JSON.parse(lrcData);
        if (lrc.content) return { lyric: Buffer.from(lrc.content, 'base64').toString('utf8'), source: 'kugou' };
    } catch (e) {}
    return null;
}

// 多候选：取搜索前 limit 首，每首歌词作为一个候选返回
async function fetchKugouMulti(limit) {
    const out = [];
    try {
        const kw = cleanKeyword(title);
        const enc = encodeURIComponent(kw);
        const searchData = await fetchUrl(
            `https://songsearch.kugou.com/song_search_v2?keyword=${enc}&page=1&pagesize=${limit}&platform=WebFilter&format=json`,
            { 'Referer': KUGOU_REFERER }
        );
        const search = JSON.parse(searchData);
        const lists = search.data?.lists || [];
        for (let i = 0; i < Math.min(limit, lists.length); i++) {
            const item = lists[i];
            const hash = item.FileHash;
            if (!hash) continue;
            try {
                const lyricData = await fetchUrl(
                    `https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&hash=${hash}`,
                    { 'Referer': KUGOU_REFERER }
                );
                const lyric = JSON.parse(lyricData);
                const candidate = lyric.candidates?.[0];
                if (!candidate) continue;
                const lrcData = await fetchUrl(
                    `https://lyrics.kugou.com/download?ver=1&client=pc&id=${candidate.id}&accesskey=${candidate.accesskey}&fmt=lrc&charset=utf8`,
                    { 'Referer': KUGOU_REFERER }
                );
                const lrc = JSON.parse(lrcData);
                if (lrc.content) {
                    const singer = item.SingerName || item.singername || '';
                    const name = item.SongName || item.songname || '未知';
                    out.push({ lyric: Buffer.from(lrc.content, 'base64').toString('utf8'), source: 'kugou', title: name + (singer ? ' - ' + singer : '') });
                }
            } catch (e) {}
        }
    } catch (e) {}
    return out;
}

// 平台名 → 取词函数（单条）
const PROVIDERS = { qq: fetchQQ, kugou: fetchKugou };
// 平台名 → 取词函数（多候选）
const MULTI = { qq: fetchQQMulti, kugou: fetchKugouMulti };

/**
 * 按传入顺序逐个尝试，前一个失败（返回 null）才试下一个。
 * 严格尊重用户在设置里指定的来源优先级——不再强制把 kugou 排到 qq 前面。
 * 之前强制重排会导致用户配置的顺序失效（bug 修复）。
 */
function prioritize(names) {
    return [...names];
}

async function tryInOrder(names) {
    for (const name of prioritize(names)) {
        const fn = PROVIDERS[name];
        if (!fn) continue;
        try {
            const result = await fn();
            if (result) return result;
        } catch (e) {}
    }
    return null;
}

// 不指定顺序时沿用"谁快用谁"，但默认让可用的酷狗优先
function raceProviders(providers, timeout = 10000) {
    return new Promise((resolve) => {
        let settled = false;
        let finished = 0;
        const done = (value) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            resolve(value);
        };
        const timer = setTimeout(() => done(null), timeout);
        providers.forEach(async (p) => {
            try {
                const result = await p();
                if (result) { done(result); return; }
            } catch (e) {}
            // 每个 provider 结束（失败也算）就计数，全部失败立即 resolve，不必等满超时
            if (++finished >= providers.length) done(null);
        });
    });
}

(async () => {
    // 第 3 个参数：按序尝试的平台（逗号分隔）。未传则维持默认的"酷狗优先"。
    const order = (process.argv[3] || '')
        .split(',')
        .map(s => s.trim())
        .filter(s => s.length > 0);

    // 第 4 个参数：multi:N → 多候选模式，每个来源输出搜索前 N 首匹配歌词
    const mode = (process.argv[4] || '');
    if (mode.startsWith('multi')) {
        const limit = parseInt((mode.split(':')[1] || '5'), 10) || 5;
        let results = [];
        if (order.length) {
            for (const name of prioritize(order)) {
                const fn = MULTI[name];
                if (fn) results = results.concat(await fn(limit));
            }
        } else {
            results = (await fetchKugouMulti(limit)).concat(await fetchQQMulti(limit));
        }
        if (results.length) {
            for (const r of results) {
                const clean = r.lyric
                    .replace(/\\r/g, '\r').replace(/\\n/g, '\n')
                    .replace(/\\\\/g, '\\').replace(/\r\n/g, '\n')
                    .replace(/\n{3,}/g, '\n\n');
                process.stdout.write('@@CANDIDATE@@\n');
                process.stdout.write('SOURCE:' + r.source + '\n');
                process.stdout.write('TITLE:' + (r.title || '') + '\n');
                process.stdout.write(clean);
                process.stdout.write('\n@@END@@\n');
            }
        } else {
            process.stdout.write('ERROR:not found\n');
        }
        process.exit(0);
    }

    const result = order.length > 0
        ? await tryInOrder(order)
        : await raceProviders([fetchKugou, fetchQQ]);
    if (result) {
        // ★ 清洗后再输出
        let cleanLyric = result.lyric
            .replace(/\\r/g, '\r')
            .replace(/\\n/g, '\n')
            .replace(/\\\\/g, '\\')
            .replace(/\r\n/g, '\n')
            .replace(/\n{3,}/g, '\n\n');
        process.stdout.write('SOURCE:' + result.source + '\n');
        process.stdout.write(cleanLyric);
    } else {
        process.stdout.write('ERROR:not found\n');
    }
})();
