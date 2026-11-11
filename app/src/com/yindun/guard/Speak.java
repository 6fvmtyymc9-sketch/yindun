package com.yindun.guard;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

/**
 * Speak —— 播女儿的声音
 * ============================================================
 * 【为什么一定要用预录，不能用机器合成音】
 *
 * 阻断那一刻，老人的手机响一句话：
 *     "妈，是我。别转钱。"
 *
 * 如果是 TTS 合成的女声，骗子会当场拆台：
 *     "妈，那是诈骗软件，机器人的声音，快关掉。"
 * 老人正被他牵着，信谁？
 *
 * 但如果是女儿本人的声音，骗子没话说了 ——
 * 他没法解释"为什么你闺女的声音在骂你"。
 *
 * 所以预录的作用不是传达信息，是**证明身份**。
 * 内容就那么一句，五秒以内。老人的警觉系统针对的是陌生人，
 * 女儿的声音一响，那套警觉会重新上线。
 *
 * 【三个约束】
 * 1. 必须存本地（assets/voice/*.mp3）。阻断那一刻还要下载，网不好就废了。
 * 2. 第一句必须报身份（"妈，是我"），不能说"银盾提醒您"。
 * 3. 必须 5 秒内说完。骗子一直在耳朵里说话，超过 5 秒老人注意力就跑了。
 *
 * 【没有录音文件时】
 * 静默跳过，不报错。因为录音要人在绑定阶段录，没录也不能让 App 崩。
 * ============================================================
 */
public class Speak {

    private static final String TAG = "YinDun";

    /** 三句话的编号，对应 assets/voice/ 下的文件名 */
    public static final String STOP   = "stop";     // 通用：妈，是我，别转钱
    public static final String MOVING = "moving";    // 已经在动钱：停下来，什么都别点
    public static final String CODE   = "code";      // 验证码：那个数字别念给任何人

    private static MediaPlayer player = null;
    /** 播放中要保持打开的文件句柄，播完才关 */
    private static AssetFileDescriptor openAfd = null;

    /**
     * 没有预录文件时，用系统 TTS 顶上。
     *
     * 【为什么留这个兜底】
     * 预录音要女儿自己在绑定阶段录。录不到、或者换手机 asset 丢了，
     * 盖屏那一刻就彻底没声音——老人只看见一块红屏，不知道谁在叫她。
     *
     * TTS 的缺点（骗子能拆台说"那是机器音"）在演示阶段可以接受：
     * 有声音 + 屏幕上有字，比静悄悄强。真机上线还是要求录真人语音。
     */
    private static TextToSpeech tts = null;
    private static boolean ttsReady = false;
    private static boolean ttsFailed = false;
    private static String pendingText = null;

    /** 三句话的兜底文稿（只在没有预录文件时用） */
    private static String fallbackText(String key) {
        if (CODE.equals(key))   return "妈，是我。那个数字别念给任何人听。";
        if (MOVING.equals(key)) return "妈，是我。先停下来，什么都别点。";
        return "妈，是我。别转钱，等我过来。";
    }

    private static boolean speakWithTts(Context ctx, final String key) {
        if (ttsFailed) return false;
        final String text = fallbackText(key);
        pendingText = text;

        if (tts != null && ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "yindun");
            Log.i(TAG, "播放 TTS 兜底：" + text);
            return true;
        }
        if (tts == null) {
            tts = new TextToSpeech(ctx.getApplicationContext(), new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    if (status != TextToSpeech.SUCCESS) {
                        ttsFailed = true;
                        Log.w(TAG, "TTS 初始化失败，这次真的没声音（请在 assets/voice 放预录音）");
                        return;
                    }
                    int r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        ttsFailed = true;
                        Log.w(TAG, "TTS 不支持中文，跳过（请在 assets/voice 放预录音）");
                        return;
                    }
                    ttsReady = true;
                    if (pendingText != null) {
                        tts.speak(pendingText, TextToSpeech.QUEUE_FLUSH, null, "yindun");
                        Log.i(TAG, "播放 TTS 兜底（延迟就绪）：" + pendingText);
                    }
                }
            });
        }
        return true;
    }

    /**
     * 播一句。
     *
     * @param key stop / moving / code
     */
    public static void play(Context ctx, String key) {
        stop();   // 前一先在响就先掐掉，别叠着

        // ---------- 先把媒体音量拉到最大 ----------
        // 通话中骗子一直在说话，我们的声音必须盖过他。
        // 不拉满，老人根本听不清。
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0);
            }
        } catch (Exception ignored) {
        }

        // ---------- 0. 家人自己录的那一句，优先级最高 ----------
        // 这是产品真正想让人听到的声音：不是任何录好的模板，是女儿本人。
        // 她在子女端录一次，云端存一份，老人手机提前缓存（见 FamilyVoice）。
        // 没有就往下走——预录音 → TTS，三级兜底，绝不哑火。
        if (FamilyVoice.play(ctx)) return;

        // ---------- 1. 预录音次之（产品的正式形态）----------
        if (playAsset(ctx, key)) return;

        // ---------- 2. 没录音就用 TTS 顶上 ----------
        Log.w(TAG, "没有预录音 voice/" + key + ".*，改用 TTS 兜底");
        if (speakWithTts(ctx, key)) return;

        // ---------- 3. 全都没有：安静失败，绝不崩 ----------
        Log.w(TAG, "这次没出声（预录音和 TTS 都不可用）");
    }

    /** 放 assets/voice/<key>.mp3 或 .wav，放成功返回 true */
    private static boolean playAsset(Context ctx, String key) {
        String[] names = {"voice/" + key + ".mp3", "voice/" + key + ".wav"};
        for (String name : names) {
            AssetFileDescriptor afd = null;
            try {
                afd = ctx.getAssets().openFd(name);
            } catch (Exception e) {
                continue;   // 没有这个文件，试下一个
            }
            try {
                player = new MediaPlayer();
                player.setAudioStreamType(AudioManager.STREAM_MUSIC);
                player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                player.prepare();
                player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override
                    public void onCompletion(MediaPlayer mp) {
                        player = null;
                        releasePlayer();
                    }
                });
                player.start();
                Log.i(TAG, "播放预录：" + name);
                // 注意：afd 不能在这里关。MediaPlayer 是异步播放的，
                // 一关文件描述符，播到一半就没声了（这个坑踩过一次）。
                // 统一在播放结束时由 openAfd 收尾。
                openAfd = afd;
                return true;
            } catch (Exception e) {
                Log.w(TAG, "预录播放失败：" + name + " " + e.getMessage());
                try { if (player != null) player.release(); } catch (Exception ignored) {}
                player = null;
                try { afd.close(); } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private static void releasePlayer() {
        try { if (player != null) player.release(); } catch (Exception ignored) {}
        player = null;
        try { if (openAfd != null) openAfd.close(); } catch (Exception ignored) {}
        openAfd = null;
    }

    public static void stop() {
        FamilyVoice.stop();   // 家人录音用的是另一个播放器，得一起掐
        if (tts != null && ttsReady) {
            try { tts.stop(); } catch (Exception ignored) {}
        }
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (Exception ignored) {}
        }
        releasePlayer();
    }
}
