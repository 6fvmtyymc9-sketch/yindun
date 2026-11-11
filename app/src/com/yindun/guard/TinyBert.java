package com.yindun.guard;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.mindspore.Model;
import com.mindspore.MSTensor;
import com.mindspore.config.CpuBindMode;
import com.mindspore.config.DeviceType;
import com.mindspore.config.MSContext;
import com.mindspore.config.ModelType;
import com.mindspore.config.NativeLibrary;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;

/**
 * 端侧诈骗短信分类模型。
 *
 * <p>模型来源见 docs/模型选型.md 4.1：基座 huawei-noah/TinyBERT_4L_zh，用 ChiFraud 难负样本 +
 * cases.json + 合成样本在 MindSpore 上微调（tools/train_tinybert_ms.py），
 * 导出 MindIR 后用 converter_lite 转成 MindSpore Lite（assets/tinybert_cls.ms）。
 *
 * <p><b>为什么要有它</b>：规则引擎在留出集上漏报 60%——骗子换个说法，关键词一个都不命中。
 * 小模型看的是整句语义，能补上这一块，而且离线、不花钱。
 *
 * <p><b>怎么用</b>：只和规则<b>并联</b>——任一判为可疑就提醒，模型不能否决规则的判断。
 * 这也是 docs/模型选型.md 4.1「部署」一栏的要求。
 *
 * <p><b>跑不了的设备</b>：MindSpore Lite 官方只提供 arm64-v8a / armeabi-v7a 的 so，
 * x86_64 模拟器上没有对应的原生库。这种情况 {@link #score} 返回 -1，
 * 判定链自动退回纯规则，{@link #status} 会如实说明原因，绝不假装模型在跑。
 */
public final class TinyBert {

    private static final String TAG = "YindunTinyBert";

    private static final String MODEL_ASSET = "tinybert_cls.ms";
    private static final String VOCAB_ASSET = "tinybert_vocab.txt";
    private static final String MODEL_FILE = "tinybert_cls.ms";

    /** 训练与评估时用的最大长度，改了必须同步重训模型 */
    private static final int MAX_LEN = 64;

    /**
     * 判定阈值。取自留出集上的实测结论（docs/模型对比实验.md）：
     * 0.5 时漏报 5%、误报 6.7%，与规则并联后整体漏报 0~5%、误报 6.7%。
     * 调高会漏更多、误报更少；这个点和训练时评估用的口径保持一致。
     */
    private static final float THRESHOLD = 0.5f;

    private static final int STATE_IDLE = 0;
    private static final int STATE_READY = 1;
    private static final int STATE_OFF = 2;

    private static volatile int state = STATE_IDLE;
    private static volatile String offReason = "尚未初始化";

    /**
     * 加载和推理用两把锁。预热要拷 44MB 模型，会长时间占着加载锁；
     * 这期间来的短信不该被卡在推理锁上等，而是直接按「模型缺席」走规则。
     */
    private static final Object WARM_LOCK = new Object();
    private static final Object INFER_LOCK = new Object();

    private static volatile Model model;
    private static MSContext msCtx;
    private static HashMap<String, Integer> vocab;

    private static int idCls = 101;
    private static int idSep = 102;
    private static int idUnk = 100;
    private static int idPad = 0;

    private TinyBert() {
    }

    // ---------------------------------------------------------------- 加载

    /**
     * 预热。放在 GuardService.onCreate 里调，别等第一条短信到了才现加载——
     * 首次要从 assets 拷 44MB 模型出来，慢的话上百毫秒到秒级。
     *
     * @return 是否可用
     */
    public static boolean warmUp(Context ctx) {
        if (state == STATE_READY) return true;
        if (state == STATE_OFF) return false;

        synchronized (WARM_LOCK) {
            // 等锁期间可能已经被别的线程加载好了
            if (state == STATE_READY) return true;
            if (state == STATE_OFF) return false;
            return doWarmUp(ctx);
        }
    }

    private static boolean doWarmUp(Context ctx) {
        Context app = ctx.getApplicationContext();
        try {
            // ① 先看这台机器能不能跑：官方 so 只有 arm
            if (!armSupported()) {
                off("当前设备架构为 " + Build.SUPPORTED_ABIS[0]
                        + "，MindSpore Lite 只提供 arm64-v8a / armeabi-v7a 原生库");
                return false;
            }

            // ② 词表
            long t0 = System.currentTimeMillis();
            loadVocab(app);

            // ③ 模型（首次落盘）
            File msFile = ensureModelFile(app);

            // ④ 原生库 + 推理引擎
            NativeLibrary.load();

            msCtx = new MSContext();
            if (!msCtx.init()) {
                throw new IllegalStateException("MSContext 初始化失败");
            }
            // 必须显式注册计算设备，否则 Lite 内部 VerifyDeviceInfo 会报
            // "Device info list is empty"，build 直接返回 -3。
            // 短信判定是低频动作（一天几条），绑 2 个中核就够，不用抢满整个 CPU。
            if (!msCtx.addDeviceInfo(DeviceType.DT_CPU, false, CpuBindMode.MID_CPU)) {
                throw new IllegalStateException("注册 CPU 设备失败");
            }
            msCtx.setThreadNum(2);

            model = new Model();
            if (!model.build(msFile.getAbsolutePath(), ModelType.MT_MINDIR_LITE, msCtx)) {
                throw new IllegalStateException("模型加载失败：" + msFile.getAbsolutePath());
            }

            state = STATE_READY;
            Log.i(TAG, "端侧模型就绪，用时 " + (System.currentTimeMillis() - t0) + "ms，"
                    + "输入 " + describeTensors(model.getInputs())
                    + "，输出 " + describeTensors(model.getOutputs()));
            return true;

        } catch (Throwable e) {
            // 这里连 UnsatisfiedLinkError 都要接住：模型不可用不能让短信提醒跟着挂
            off(e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 给一句话打分。
     *
     * @return 诈骗概率 0~1；<b>模型不可用时返回 -1</b>（调用方据此知道「这一票缺席」，
     * 而不是「模型判了不诈骗」）
     */
    public static float score(String text) {
        // 锁外先快速判一次：预热正在拷 44MB 模型时会长时间持有锁，
        // 短信广播线程不能在那儿等着 —— 等不到就按「模型缺席」走规则，比卡住强。
        if (state != STATE_READY || model == null) return -1f;
        if (text == null) return -1f;

        synchronized (INFER_LOCK) {
            return infer(text);
        }
    }

    private static float infer(String text) {
        if (state != STATE_READY || model == null) return -1f;
        try {
            int[] ids = new int[MAX_LEN];
            float[] mask = new float[MAX_LEN];
            encode(text, ids, mask);

            // 加性 mask：被遮住的位置加一个很大的负数，softmax 后权重约等于 0
            float[] addMask = new float[MAX_LEN];
            for (int i = 0; i < MAX_LEN; i++) {
                addMask[i] = mask[i] > 0 ? 0f : -1e9f;
            }

            MSTensor idsTensor = null;
            MSTensor maskTensor = null;
            for (MSTensor t : model.getInputs()) {
                // ids 是 [1, MAX_LEN]，加性 mask 是 [1, 1, 1, MAX_LEN]，用维数区分最稳
                if (t.getShape().length <= 2) idsTensor = t;
                else maskTensor = t;
            }
            if (idsTensor == null || maskTensor == null) {
                Log.w(TAG, "输入张量对不上：ids=" + idsTensor + " mask=" + maskTensor);
                return -1f;
            }

            idsTensor.setData(ids);
            maskTensor.setData(addMask);

            if (!model.predict()) {
                Log.w(TAG, "推理失败");
                return -1f;
            }

            List<MSTensor> outs = model.getOutputs();
            if (outs == null || outs.isEmpty()) return -1f;
            float[] logits = outs.get(0).getFloatData();
            if (logits == null || logits.length < 2) return -1f;

            return softmaxPositive(logits[0], logits[1]);

        } catch (Throwable e) {
            Log.w(TAG, "打分异常：" + e);
            return -1f;
        }
    }

    /** 这条短信在当前阈值下是否该被模型判为可疑。模型缺席时返回 false（不改变规则结论）。 */
    public static boolean suspicious(String text) {
        float p = score(text);
        return p >= 0 && p >= THRESHOLD;
    }

    public static float threshold() {
        return THRESHOLD;
    }

    public static boolean ready() {
        return state == STATE_READY;
    }

    /** 供界面/诊断用的一句话状态。 */
    public static String status() {
        if (state == STATE_READY) return "端侧模型已加载（TinyBERT 4L，离线）";
        return "端侧模型未启用：" + offReason;
    }

    // ---------------------------------------------------------------- 内部

    private static void off(String reason) {
        state = STATE_OFF;
        offReason = reason;
        Log.w(TAG, "端侧模型不可用：" + reason);
    }

    private static boolean armSupported() {
        if (Build.SUPPORTED_ABIS == null) return false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi) || "armeabi-v7a".equals(abi) || "armeabi".equals(abi)) {
                return true;
            }
        }
        return false;
    }

    private static void loadVocab(Context ctx) throws Exception {
        if (vocab != null) return;
        HashMap<String, Integer> map = new HashMap<>(32768);
        BufferedReader br = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open(VOCAB_ASSET), "UTF-8"));
        try {
            String line;
            int i = 0;
            while ((line = br.readLine()) != null) {
                map.put(line, i);
                i++;
            }
        } finally {
            br.close();
        }
        Integer c = map.get("[CLS]"), s = map.get("[SEP]"), u = map.get("[UNK]"), p = map.get("[PAD]");
        if (c != null) idCls = c;
        if (s != null) idSep = s;
        if (u != null) idUnk = u;
        if (p != null) idPad = p;
        vocab = map;
        Log.i(TAG, "词表加载完成，共 " + map.size() + " 条"
                + "（[CLS]=" + idCls + " [SEP]=" + idSep + " [UNK]=" + idUnk + "）");
    }

    /** assets 里的模型拷到应用私有目录，Lite 只能从文件路径加载。已存在且大小一致就复用。 */
    private static File ensureModelFile(Context ctx) throws Exception {
        File out = new File(ctx.getFilesDir(), MODEL_FILE);
        long assetSize = -1;
        InputStream probe = ctx.getAssets().open(MODEL_ASSET);
        try {
            assetSize = probe.available();
        } catch (Exception ignored) {
            // available() 对压缩的 asset 不一定准，拿不到就退回按存在性判断
        } finally {
            probe.close();
        }

        if (out.exists() && assetSize > 0 && out.length() == assetSize) {
            return out;
        }

        InputStream in = ctx.getAssets().open(MODEL_ASSET);
        FileOutputStream fos = new FileOutputStream(out);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.flush();
        } finally {
            try {
                fos.close();
            } catch (Exception ignored) {
            }
            in.close();
        }
        Log.i(TAG, "模型已释放到 " + out.getAbsolutePath() + "（" + out.length() + " 字节）");
        return out;
    }

    /**
     * 字级切分，必须和训练脚本里的 CharTokenizer 逐字一致，否则分数对不上。
     * ASCII 的连续字母/数字合成一个 token（"10086" 不该被拆散），其余按单字切。
     */
    private static void encode(String text, int[] ids, float[] mask) {
        int[] buf = new int[MAX_LEN];
        int len = 0;
        buf[len++] = idCls;

        StringBuilder ascii = new StringBuilder();
        int limit = MAX_LEN - 2;          // 留出 [CLS] 和 [SEP]
        int textLen = text.length();

        for (int i = 0; i < textLen && (len - 1) < limit; i++) {
            char ch = text.charAt(i);
            if (ch < 128 && Character.isLetterOrDigit(ch)) {
                ascii.append(ch);
                continue;
            }
            if (ascii.length() > 0) {
                len = pushToken(ascii.toString(), buf, len, limit);
                ascii.setLength(0);
                if ((len - 1) >= limit) break;
            }
            len = pushToken(String.valueOf(ch), buf, len, limit);
        }
        if (ascii.length() > 0 && (len - 1) < limit) {
            len = pushToken(ascii.toString(), buf, len, limit);
        }
        if (len < MAX_LEN) buf[len++] = idSep;

        for (int i = 0; i < MAX_LEN; i++) {
            if (i < len) {
                ids[i] = buf[i];
                mask[i] = 1f;
            } else {
                ids[i] = idPad;
                mask[i] = 0f;
            }
        }
    }

    private static int pushToken(String token, int[] buf, int len, int limit) {
        if ((len - 1) >= limit || len >= MAX_LEN - 1) return len;
        Integer id = vocab == null ? null : vocab.get(token);
        buf[len] = (id == null ? idUnk : id);
        return len + 1;
    }

    private static float softmaxPositive(float a, float b) {
        double ea = Math.exp(a - Math.max(a, b));
        double eb = Math.exp(b - Math.max(a, b));
        return (float) (eb / (ea + eb));
    }

    private static String describeTensors(List<MSTensor> list) {
        if (list == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i).tensorName() == null ? "?" : list.get(i).tensorName());
            sb.append(shape(list.get(i).getShape()));
        }
        return sb.append("]").toString();
    }

    private static String shape(int[] s) {
        if (s == null) return "()";
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < s.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(s[i]);
        }
        return sb.append(")").toString();
    }
}
