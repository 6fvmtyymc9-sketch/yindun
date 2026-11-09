package com.yindun.guard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================================
 *  ReportActivity —— 上报可疑信息（原生界面）
 * ----------------------------------------------------------------------------
 *  这是"一键上传"功能的手机端入口。老人（或子女）把收到的诈骗短信甩进来，
 *  云端判完直接告诉他：这是第几步、什么套路、下一步会怎么骗、现在该干嘛。
 *
 *  三个输入方式，覆盖三种真实场景：
 *    1. 从最近短信里挑一条 —— 老人最省事，两步点完
 *    2. 手动粘贴文字       —— 子女在微信里看到可疑消息，转过来
 *    3. 从相册选截图       —— 截图存在相册里，OCR 交给云端多模态，App 里不做
 *
 *  为什么截图不在手机端做 OCR：
 *    手机端 OCR 要带一个几十兆的模型，装包体积翻几倍，识别率还不如云端多模态；
 *    而我们本来就要连云端判定，顺手把图发过去，一次网络请求解决两件事。
 *
 *  为什么判分不放在本页：
 *    本地 Judge 只看文字、只能算到"命中几个词"，看不懂截图，也读不出语境。
 *    本页只负责采集与展示，判定一律以云端返回为准，本地结论仅作断网兜底。
 *
 *  断网策略：请求失败不清空输入，只在结果区提示"连不上云端"，让老人改天再试，
 *    而不是把已经打好的字弄丢——这一点比"界面好看"重要得多。
 * ============================================================================
 */
public class ReportActivity extends Activity {

    private static final String TAG = "Report";

    // ---------- 配色（和主界面、演练页一套） ----------
    private static final int C_BG      = Color.parseColor("#070b16");
    private static final int C_PANEL   = Color.parseColor("#0f1524");
    private static final int C_PANEL2  = Color.parseColor("#141c30");
    private static final int C_LINE    = Color.parseColor("#1e293b");
    private static final int C_TXT     = Color.parseColor("#e5e7eb");
    private static final int C_TXT2    = Color.parseColor("#94a3b8");
    private static final int C_TXT3    = Color.parseColor("#64748b");
    private static final int C_ACCENT  = Color.parseColor("#22d3ee");
    private static final int C_OK      = Color.parseColor("#22c55e");
    private static final int C_BAD     = Color.parseColor("#ef4444");
    private static final int C_WARN    = Color.parseColor("#f59e0b");

    private static final int REQ_PICK = 1001;

    // ---------- 界面骨架 ----------
    private LinearLayout body;
    private TextView titleSub;

    // ---------- 输入控件 ----------
    private EditText etNumber;
    private EditText etText;
    private TextView picHint;       // "已选截图 · 320KB" / "还没选截图"
    private TextView btnPick;
    private TextView btnSubmit;

    // ---------- 状态 ----------
    /** 已选图片的 data URL（data:image/jpeg;base64,...），没选就是 null */
    private String imageDataUrl = null;
    /** 已选图片的原始压缩后字节数，只用于界面显示 */
    private int imageBytes = 0;
    private boolean busy = false;

    // ============================================================================
    //  生命周期
    // ============================================================================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildShell();
        showMain();
        loadHistory();
    }

    // ============================================================================
    //  界面骨架
    // ============================================================================

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // ---------- 顶栏 ----------
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(14), dp(10), dp(12));
        top.setBackgroundColor(C_PANEL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText("📮 上报可疑信息");
        t1.setTextColor(C_TXT);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        titleSub = new TextView(this);
        titleSub.setText("发过来，AI 帮你看看是不是骗子");
        titleSub.setTextColor(C_TXT3);
        titleSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        titles.addView(t1);
        titles.addView(titleSub);
        top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView gear = new TextView(this);
        gear.setText("⚙");
        gear.setTextColor(C_TXT2);
        gear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        gear.setPadding(dp(12), dp(6), dp(4), dp(6));
        gear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showServerDialog(); }
        });
        top.addView(gear);

        TextView back = new TextView(this);
        back.setText("返回");
        back.setTextColor(C_ACCENT);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        back.setPadding(dp(10), dp(8), dp(8), dp(8));
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        top.addView(back);

        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------- 内容区（可滚动） ----------
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(16), dp(16), dp(28));
        sv.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    // ============================================================================
    //  主界面
    // ============================================================================

    private void showMain() {
        body.removeAllViews();

        // ---------- 卡片一：这条信息长什么样 ----------
        body.addView(sectionTitle("这条信息长什么样"));

        LinearLayout c1 = card();

        c1.addView(label("对方号码（可以不填）", 11, C_TXT3, false));
        etNumber = input("1069xxxxx / 陌生手机号", false);
        etNumber.setInputType(InputType.TYPE_CLASS_PHONE);
        LinearLayout.LayoutParams lpN = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpN.topMargin = dp(6);
        c1.addView(etNumber, lpN);

        TextView tvT = label("短信 / 聊天内容", 11, C_TXT3, false);
        tvT.setPadding(0, dp(14), 0, 0);
        c1.addView(tvT);
        etText = input("把骗子说的话粘在这里…", true);
        LinearLayout.LayoutParams lpTx = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpTx.topMargin = dp(6);
        c1.addView(etText, lpTx);

        // ---------- 三个输入方式的按钮 ----------
        TextView tvWays = label("或者换种方式给我", 11, C_TXT3, false);
        tvWays.setPadding(0, dp(16), 0, dp(8));
        c1.addView(tvWays);

        LinearLayout wayRow = new LinearLayout(this);
        wayRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView btnSms = smallBtn("📩 从最近短信选");
        btnSms.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickFromSms(); }
        });
        wayRow.addView(btnSms, weightLp());

        btnPick = smallBtn("🖼 选截图");
        btnPick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickImage(); }
        });
        LinearLayout.LayoutParams lpP = weightLp();
        lpP.leftMargin = dp(8);
        wayRow.addView(btnPick, lpP);

        c1.addView(wayRow);

        picHint = label("还没选截图", 11, C_TXT3, false);
        picHint.setPadding(0, dp(8), 0, 0);
        c1.addView(picHint);

        body.addView(c1);

        // ---------- 卡片二：提交 ----------
        LinearLayout c2 = card();
        btnSubmit = bigBtn("开始识别");
        btnSubmit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { submit(); }
        });
        c2.addView(btnSubmit);

        TextView tip = label("图片和文字至少给一样。给了文字会先用本地规则判一次，秒出结果；"
                + "只有图片时交给云端多模态读。", 11, C_TXT3, false);
        tip.setPadding(0, dp(10), 0, 0);
        c2.addView(tip);
        body.addView(c2);

        // ---------- 结果区（提交后才出现） ----------
        // 用 tag 占位，方便 submit 后往上插卡片
    }

    // ============================================================================
    //  输入控件工厂
    // ============================================================================

    private EditText input(String hint, boolean multi) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(C_TXT3);
        e.setTextColor(C_TXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setBackground(roundRect(C_PANEL2, C_LINE, 1));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (multi) {
            e.setMinLines(4);
            e.setGravity(Gravity.TOP | Gravity.START);
            e.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        } else {
            e.setSingleLine(true);
        }
        return e;
    }

    private TextView smallBtn(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(C_ACCENT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(10), dp(10), dp(10));
        t.setBackground(roundRect(0xFF0d1b2a, C_ACCENT, 1));
        return t;
    }

    private TextView bigBtn(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.parseColor("#04121a"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(14), dp(12), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_ACCENT);
        g.setCornerRadius(dp(12));
        t.setBackground(g);
        return t;
    }

    private LinearLayout.LayoutParams weightLp() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    // ============================================================================
    //  方式一：从最近短信里挑
    // ============================================================================

    /**
     * 读收件箱最近 10 条给老人挑。
     * 不筛发件人、不做本地预判 —— 让老人自己决定"哪条看着不对"，
     * 我们不能替他判断，也不能因为本地规则觉得没事就不给他看。
     */
    private void pickFromSms() {
        final List<String> addrs = new ArrayList<String>();
        final List<String> bodies = new ArrayList<String>();
        try {
            ContentResolver cr = getContentResolver();
            Cursor c = cr.query(Uri.parse("content://sms/inbox"),
                    new String[]{"address", "body"}, null, null, "date DESC");
            if (c != null) {
                while (c.moveToNext() && addrs.size() < 10) {
                    String a = c.getString(0);
                    String bd = c.getString(1);
                    addrs.add(a == null ? "未知号码" : a);
                    bodies.add(bd == null ? "" : bd);
                }
                c.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "读短信失败：" + e.getMessage());
            toast("读不到短信，请检查「短信」权限，或直接粘贴内容");
            return;
        }

        if (addrs.isEmpty()) {
            toast("收件箱里没有短信，直接粘贴内容吧");
            return;
        }

        CharSequence[] items = new CharSequence[addrs.size()];
        for (int i = 0; i < addrs.size(); i++) {
            String bd = bodies.get(i).replace('\n', ' ');
            if (bd.length() > 34) bd = bd.substring(0, 34) + "…";
            items[i] = addrs.get(i) + "\n" + bd;
        }

        new AlertDialog.Builder(this)
                .setTitle("挑一条看看")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        etNumber.setText(addrs.get(which));
                        etText.setText(bodies.get(which));
                        toast("已填入，点「开始识别」");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ============================================================================
    //  方式二：从相册选截图
    // ============================================================================

    private void pickImage() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, "选一张截图"), REQ_PICK);
        } catch (Exception e) {
            toast("打不开相册：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_PICK || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        picHint.setText("正在压缩图片…");
        new Thread(new Runnable() {
            public void run() {
                final String url = encodeImage(uri);
                ui(new Runnable() {
                    public void run() {
                        if (url == null) {
                            imageDataUrl = null;
                            imageBytes = 0;
                            picHint.setText("这张图读不出来，换一张试试");
                            picHint.setTextColor(C_BAD);
                        } else {
                            imageDataUrl = url;
                            imageBytes = (int) ((url.length() - 23) * 0.75);
                            picHint.setText("已选截图 · " + (imageBytes / 1024) + "KB · 点「开始识别」");
                            picHint.setTextColor(C_OK);
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 把相册里的图压到最长边 1600px 的 JPEG 再转 base64。
     *
     * 为什么是 1600：短信截图里的字，1600px 足够模型看清；
     * 再大只是白白多花 token 和流量，老人套餐经不起。
     * 为什么是 JPEG 85：再高压根看不出区别，再低字会糊。
     */
    private String encodeImage(Uri uri) {
        InputStream is = null;
        try {
            // 第一遍只读尺寸，不占内存
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            is = getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(is, null, o);
            is.close();

            int max = Math.max(o.outWidth, o.outHeight);
            int sample = 1;
            while (max / sample > 1600) sample *= 2;

            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            is = getContentResolver().openInputStream(uri);
            Bitmap bm = BitmapFactory.decodeStream(is, null, o2);
            is.close();
            if (bm == null) return null;

            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, 85, bo);
            bm.recycle();

            String b64 = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
            return "data:image/jpeg;base64," + b64;
        } catch (Exception e) {
            Log.w(TAG, "压缩图片失败：" + e.getMessage());
            return null;
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignored) { }
        }
    }

    // ============================================================================
    //  提交
    // ============================================================================

    private void submit() {
        if (busy) return;

        final String num = etNumber.getText().toString().trim();
        final String text = etText.getText().toString().trim();

        if (text.length() == 0 && imageDataUrl == null) {
            toast("图片和文字至少给一样");
            return;
        }
        if (!DrillNet.hasBase(this)) {
            showServerDialog();
            return;
        }

        busy = true;
        btnSubmit.setText("识别中…");
        btnSubmit.setAlpha(0.6f);
        titleSub.setText("正在问云端…");

        new Thread(new Runnable() {
            public void run() {
                JSONObject req = new JSONObject();
                try {
                    req.put("elderId", "demo");
                    // 本页是老人手机上的原生页，提交身份固定为 elder。
                    // 子女代传走的是网页端（?role=child），那边传 child。
                    req.put("reporter", "elder");
                    if (num.length() > 0) req.put("number", num);
                    if (text.length() > 0) req.put("text", text);
                    if (imageDataUrl != null) req.put("image", imageDataUrl);
                } catch (Exception ignored) { }

                final JSONObject r = DrillNet.post(ReportActivity.this, "/api/report", req);

                ui(new Runnable() {
                    public void run() {
                        busy = false;
                        btnSubmit.setText("开始识别");
                        btnSubmit.setAlpha(1f);
                        titleSub.setText("发过来，AI 帮你看看是不是骗子");

                        if (r == null || !r.optBoolean("ok", false)) {
                            String msg = r == null ? null : r.optString("msg", null);
                            showFail(msg != null ? msg : "连不上云端。检查一下 WiFi，或者点右上角 ⚙ 看服务器地址填对没有。");
                            return;
                        }
                        showResult(r);
                        loadHistory();
                    }
                });
            }
        }).start();
    }

    private void showFail(String msg) {
        body.removeAllViews();
        showMain();

        LinearLayout c = card();
        c.setBackground(roundRect(0xFF2a1214, C_BAD, 1));
        TextView t = label("这次没成功", 15, C_BAD, true);
        c.addView(t);
        TextView d = label(msg, 13, C_TXT2, false);
        d.setPadding(0, dp(8), 0, 0);
        d.setLineSpacing(dp(3), 1f);
        c.addView(d);
        TextView t2 = label("刚才填的内容还在，改完可以直接再点一次。", 11, C_TXT3, false);
        t2.setPadding(0, dp(10), 0, 0);
        c.addView(t2);
        body.addView(c);
    }

    // ============================================================================
    //  结果渲染
    // ============================================================================

    /**
     * 结果卡片按"老人能看懂"的顺序排：
     *   第一眼：有没有事（红/绿大字）
     *   第二眼：骗到哪一步了（进度条）
     *   第三眼：为什么（骗子用了什么话术）
     *   最后：现在该干嘛
     * 技术细节（耗时、花费、token）压到最底下一行小字，不影响老人阅读。
     */
    private void showResult(JSONObject r) {
        body.removeAllViews();
        showMain();

        final int stage = r.optInt("stage", 0);
        final boolean fraud = r.optBoolean("isFraud", false);
        final int color = fraud ? (stage >= 3 ? C_BAD : C_WARN) : C_OK;

        // ---------- 结论 ----------
        LinearLayout c = card();
        c.setBackground(roundRect(fraud ? 0xFF2a1214 : 0xFF0d2418,
                fraud ? C_BAD : C_OK, 1));

        TextView head = label(fraud ? (stage >= 3 ? "⚠ 这是诈骗，别动钱" : "⚠ 有点可疑") : "✓ 看着不像诈骗",
                20, color, true);
        c.addView(head);

        if (fraud && stage >= 3) {
            TextView danger = label("对方正在把你往「转账」那一步带。现在挂掉、别转、打给子女。",
                    13, C_TXT, false);
            danger.setPadding(0, dp(8), 0, 0);
            danger.setLineSpacing(dp(3), 1f);
            c.addView(danger);
        }

        // ---------- 阶段进度 ----------
        c.addView(stageBar(stage, color));

        TextView st = label("第 " + stage + " 步 · " + r.optString("stageName", "—"), 13, C_TXT2, false);
        st.setPadding(0, dp(8), 0, 0);
        c.addView(st);

        body.addView(c);

        // ---------- 套路 ----------
        LinearLayout c2 = card();
        c2.addView(label("这是什么套路", 11, C_TXT3, true));
        TextView type = label(r.optString("type", "—"), 16, C_TXT, true);
        type.setPadding(0, dp(6), 0, 0);
        c2.addView(type);

        String reason = r.optString("reason", "");
        if (reason.length() > 0) {
            TextView rz = label(reason, 13, C_TXT2, false);
            rz.setPadding(0, dp(8), 0, 0);
            rz.setLineSpacing(dp(3), 1f);
            c2.addView(rz);
        }

        // 命中的特征词
        JSONArray sig = r.optJSONArray("signals");
        if (sig != null && sig.length() > 0) {
            TextView tvS = label("露馅的地方", 11, C_TXT3, true);
            tvS.setPadding(0, dp(14), 0, dp(6));
            c2.addView(tvS);
            LinearLayout wrap = new LinearLayout(this);
            wrap.setOrientation(LinearLayout.HORIZONTAL);
            wrap.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (int i = 0; i < sig.length() && i < 5; i++) {
                String w = sig.optString(i, "");
                if (w.length() == 0) continue;
                TextView chip = label(w, 11, C_WARN, false);
                chip.setPadding(dp(9), dp(5), dp(9), dp(5));
                chip.setBackground(roundRect(0xFF2a2110, C_WARN, 1));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(6);
                wrap.addView(chip, lp);
            }
            c2.addView(wrap);
        }
        body.addView(c2);

        // ---------- 该干嘛 ----------
        LinearLayout c3 = card();
        c3.setBackground(roundRect(0xFF0d1b2a, C_ACCENT, 1));
        c3.addView(label("现在怎么做", 11, C_ACCENT, true));
        TextView adv = label(r.optString("advice", "请暂缓转账，先与家人核实"), 15, C_TXT, true);
        adv.setPadding(0, dp(8), 0, 0);
        adv.setLineSpacing(dp(4), 1f);
        c3.addView(adv);
        body.addView(c3);

        // ---------- 号码信誉 ----------
        int cnt = r.optInt("reportCount", 0);
        boolean black = r.optBoolean("blacklisted", false);
        if (cnt > 0) {
            LinearLayout c4 = card();
            if (black) c4.setBackground(roundRect(0xFF2a1214, C_BAD, 1));
            c4.addView(label("这个号码", 11, C_TXT3, true));
            TextView n = label(black
                            ? "已被 " + cnt + " 个人举报过 · 已标红"
                            : "已有 " + cnt + " 个人举报过这个号码",
                    14, black ? C_BAD : C_TXT, true);
            n.setPadding(0, dp(6), 0, 0);
            c4.addView(n);
            if (!black) {
                TextView h = label("再攒 " + (3 - cnt) + " 次举报，这个号码就会被标红，"
                        + "其他人收到它的消息会提前被提醒。", 11, C_TXT3, false);
                h.setPadding(0, dp(6), 0, 0);
                h.setLineSpacing(dp(3), 1f);
                c4.addView(h);
            }
            body.addView(c4);
        }

        // ---------- 技术明细（小字，可选阅读）----------
        JSONObject meta = r.optJSONObject("meta");
        if (meta != null) {
            String m = "耗时 " + meta.optInt("ms", 0) + "ms · 花费 ¥"
                    + String.format("%.4f", meta.optDouble("cost", 0))
                    + " · " + meta.optString("model", "");
            TextView mt = label(m, 10, C_TXT3, false);
            mt.setPadding(dp(2), dp(4), 0, 0);
            body.addView(mt);
        }

        // 清空输入，避免下次误提交同一条
        etText.setText("");
        imageDataUrl = null;
        imageBytes = 0;
        picHint.setText("还没选截图");
        picHint.setTextColor(C_TXT3);
    }

    /** 0~4 步进度条：走到的格子填色，没走到的留空 */
    private View stageBar(int stage, int color) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lpBar = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        lpBar.topMargin = dp(14);
        bar.setLayoutParams(lpBar);

        for (int i = 1; i <= 4; i++) {
            View cell = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(4));
            g.setColor(i <= stage ? color : C_LINE);
            cell.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i > 1) lp.leftMargin = dp(5);
            bar.addView(cell, lp);
        }
        return bar;
    }

    // ============================================================================
    //  最近上报
    // ============================================================================

    private void loadHistory() {
        if (!DrillNet.hasBase(this)) return;
        new Thread(new Runnable() {
            public void run() {
                final JSONObject r = DrillNet.get(ReportActivity.this, "/api/reports?limit=8");
                if (r == null) return;
                final JSONArray list = r.optJSONArray("list");
                if (list == null || list.length() == 0) return;
                ui(new Runnable() {
                    public void run() { renderHistory(list); }
                });
            }
        }).start();
    }

    private void renderHistory(JSONArray list) {
        body.addView(sectionTitle("最近识别过的（大家一起攒的）"));

        LinearLayout c = card();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;

            // 兼容 RDS 里是 is_fraud、内存里是 isFraud 两种写法
            boolean fraud = o.optBoolean("is_fraud", o.optBoolean("isFraud", false));
            int stage = o.optInt("stage", 0);
            String num = o.optString("number", "");
            String ex = o.optString("excerpt", "");
            String ty = o.optString("type", "");

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(9), 0, dp(9));

            String head = (fraud ? "🔴 " : "⚪ ") + (num.length() > 0 ? num : "未填号码")
                    + "  ·  " + (fraud ? "第 " + stage + " 步" : "看着正常");
            TextView h = label(head, 13, fraud ? C_BAD : C_TXT2, true);
            row.addView(h);

            if (ex.length() > 0) {
                String cut = ex.replace('\n', ' ');
                if (cut.length() > 46) cut = cut.substring(0, 46) + "…";
                TextView e = label(cut, 12, C_TXT3, false);
                e.setPadding(0, dp(4), 0, 0);
                row.addView(e);
            }
            if (ty.length() > 0) {
                TextView t = label(ty, 11, C_WARN, false);
                t.setPadding(0, dp(3), 0, 0);
                row.addView(t);
            }

            c.addView(row);

            if (i < list.length() - 1) {
                View line = new View(this);
                line.setBackgroundColor(C_LINE);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1);
                lp.topMargin = dp(2);
                c.addView(line, lp);
            }
        }
        body.addView(c);
    }

    // ============================================================================
    //  服务器地址
    // ============================================================================

    private void showServerDialog() {
        final EditText e = new EditText(this);
        e.setHint(DrillNet.DEFAULT_BASE);
        e.setText(DrillNet.base(this));
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setPadding(dp(16), dp(12), dp(16), dp(12));

        new AlertDialog.Builder(this)
                .setTitle("云端地址")
                .setMessage("填电脑或 ECS 的地址。演练页和这页共用这一个设置，填一次就行。")
                .setView(e)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        DrillNet.setBase(ReportActivity.this, e.getText().toString());
                        toast("已保存");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ============================================================================
    //  小工具
    // ============================================================================

    private void ui(Runnable r) { runOnUiThread(r); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(13), dp(14), dp(13));
        c.setBackground(roundRect(C_PANEL, C_LINE, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView sectionTitle(String s) {
        TextView t = label(s, 11, C_TXT3, true);
        t.setPadding(dp(2), dp(14), 0, dp(8));
        return t;
    }

    private GradientDrawable roundRect(int fill, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(12));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }
}
