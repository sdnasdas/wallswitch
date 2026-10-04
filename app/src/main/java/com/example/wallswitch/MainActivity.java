package com.example.wallswitch;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.LruCache;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 主页 v3.14：层级导航 —— 首页 = 壁纸库列表，点库行进该库的两列正方形网格壁纸页。
 * 单 Activity + 页状态切换（一个 RecyclerView 换 Adapter + LayoutManager）：
 * <ul>
 *   <li>库列表页：☰ 开抽屉 + 固定标题 WallPaper；行 = 缩略图（该库当前壁纸）+ 库名
 *       （点按就地改名）+ N 张壁纸 + 启用开关 + 齿轮（范围/模式/间隔/立即切换）；
 *       长按行浮出红垃圾桶（删库二次确认）；右下角 ＋ 新建库。</li>
 *   <li>壁纸网格页：← 返回 + 库名；两列 1:1 正方形网格；点格直接进裁剪页重编，长按浮出
 *       铅笔（改标题小窗）/ 红垃圾桶（直接删，不确认）；右下角 ＋ 添加壁纸；
 *       返回键/返回手势回库列表（在库列表页返回才退出 App）。</li>
 * </ul>
 * 左侧设置抽屉只留全局项：接管情况（一个总开关 + 桌面/锁屏两行状态）、自动切换
 * （到点通知 + 上次/下次时间）、导出与日志、桌面图标预览、引擎与后台。
 */
public class MainActivity extends AppCompatActivity {

    // SharedPreferences 文件名
    private static final String PREFS_NAME = "settings";
    // 相册单次多选上限
    private static final int MAX_PICK = 50;
    // 通知权限提示是否已弹过的记录 key（避免每次打开应用都打扰）
    private static final String KEY_NOTIFY_PROMPTED = "notify_prompted";

    private ActivityResultLauncher<PickVisualMediaRequest> pickLauncher;
    // 通知权限请求（Android 13+ 自动切换提示需要 POST_NOTIFICATIONS）
    private ActivityResultLauncher<String> notifyPermissionLauncher;
    /** 读取系统壁纸存档用（Android 13+ 是 READ_MEDIA_IMAGES，低版本 READ_EXTERNAL_STORAGE） */
    private ActivityResultLauncher<String> savePermLauncher;
    /** Android 11+ 跳系统设置开「所有文件访问」，回来后检查结果继续开启接管 */
    private ActivityResultLauncher<Intent> allFilesLauncher;
    // 全局「桌面图标预览底图」的单张选图（选一次、抠图后存应用目录，之后裁剪页直接复用）
    private ActivityResultLauncher<PickVisualMediaRequest> overlayLauncher;
    // SAF 导出目录选择（ACTION_OPEN_DOCUMENT_TREE）
    private ActivityResultLauncher<Uri> exportDirLauncher;
    // 还原：用户直接指一个备份包（ACTION_OPEN_DOCUMENT，每次现授临时读权限，不依赖卸载前那个目录授权）
    private ActivityResultLauncher<String[]> restoreZipLauncher;
    // 接管开关是否处于「等待用户在系统选择器里确认」的状态（用来判断用户是否点了取消）
    private RecyclerView recycler;
    // 两页共用一个 RecyclerView：库列表页 = LibAdapter + LinearLayoutManager，
    // 壁纸网格页 = WallpaperAdapter + GridLayoutManager(2)
    private LibAdapter libAdapter;
    private WallpaperAdapter wallpaperAdapter;
    private SharedPreferences prefs;
    /** 保存当前壁纸时的不可关闭进度弹窗（showProgress/dismissTakeoverProgress 管理）。 */
    private androidx.appcompat.app.AlertDialog takeoverProgress;
    // 当前页：true = 壁纸网格页；false = 库列表页（首页）
    private boolean showingWallpapers = false;
    // 壁纸页正在看的库 id（也兼作「上次查看的库」，导入壁纸时的默认目标库）
    private String currentLibId;
    // 正在就地改名的库 id（null = 没有；返回键用它判断「取消改名」而不是退出）
    private String renamingLibId;
    // 顶栏删除按钮（库页专用）：普通态 = 垃圾桶进删除态，删除态 = 勾图标确认批量删除
    private MenuItem libDeleteItem;
    // v3.60 范围槽位（只在库列表页显示）：v3.65 起两张竖排卡片改成左右滑块，一次只显示一面
    private View slotCards;
    private RecyclerView slotPager;
    private SlotPagerAdapter slotAdapter;
    // 上次停在哪个范围（true=桌面）：滑块初始位置按它取
    private static final String KEY_SLOT_PAGE_HOME = "slot_page_home";
    /** 滑块的虚拟页数（每面各 SLOT_LOOP 份）：够大到没人滑得到头，左右都能一直翻。 */
    private static final int SLOT_LOOP = 1000;
    // 滑块停在哪个范围（拖头像设库时归属就是它），以及被高亮的卡片
    private boolean slotPageForHome = true;
    private View slotPageCard;
    private View highlightedSlotCard;
    // 库行排序拖拽（长按行的空白处手动 startDrag 起来的那套）
    private ItemTouchHelper libDragHelper;
    // 长按头像拖出的浮动头像：跟随手指，松手落在范围卡片上 = 把该库设成那个范围
    private ImageView avatarFloat;
    private View avatarOrigin;
    private String avatarDragLibId;
    private float avatarGrabX, avatarGrabY, avatarLastRawX, avatarLastRawY;
    private int[] avatarContentLoc;
    // 拖起时缓存的当前卡片窗口矩形（left, top, right, bottom），避免每帧查位置
    private int[] slotRect;
    // 推移途中还没落地的换页动作（连点时先补跑它并复位，再走新的）
    private Runnable pendingPageSwap;
    // 判定「这次按压算点击还是算滑动」的系统点击容差（约 8dp）；
    // 只能在 onCreate 里取——字段初始化发生在 attachBaseContext 之前，那时还拿不到系统服务
    private int pressSlopPx;
    // 检查更新那一行：状态文字 + App 内下载进度条（进度只在下载中轮询刷新，不下即时停）
    private TextView tvUpdateState;
    private ProgressBar pbUpdate;
    // 下载中显形的取消键：按下作废当前下载并删掉半截临时包
    private TextView btnCancelDownload;
    private final Handler updateTick = new Handler(Looper.getMainLooper());
    // 本次更新的目标（弹窗确认下载时记下）：下载失败后点这一行直接按原目标重下
    private String lastUpdateSource;
    private String lastUpdateVersion;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 点击容差要在建 holder 之前就位（Holder 构造时把它交给 PressGuard）
        pressSlopPx = ViewConfiguration.get(this).getScaledTouchSlop();
        setContentView(R.layout.activity_main);
        // 全面屏/刘海屏适配：内容避开状态栏、刘海与底部手势条（Android 15 强制边到边）
        InsetsHelper.apply(this, R.id.main_root);
        // 左侧抽屉是独立面板（不在 main_root 里），也要避开状态栏与底部手势条
        InsetsHelper.apply(this, R.id.drawer_content);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // 注册系统相册多选（PickVisualMedia，无需存储权限）
        pickLauncher = registerForActivityResult(
                new ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK),
                this::onPicked);
        // 注册通知权限请求（Android 13+）：未授权时自动切换通知发不出来
        notifyPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                this::onNotifyPermissionResult);
        savePermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                this::onSavePermissionResult);
        allFilesLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> onAllFilesAccessResult());
        overlayLauncher = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(), this::onOverlayPicked);
        exportDirLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), this::onExportDirPicked);
        restoreZipLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onRestoreZipPicked);
        recycler = findViewById(R.id.recycler);
        libAdapter = new LibAdapter();
        wallpaperAdapter = new WallpaperAdapter();
        setupLibDeleteMenu();
        setupLibDrag();
        setupSlotCards();
        setupBackPressed();
        setupDrawer();
        setupButtons();
        setupNotifySwitch();
        setupStatusNotifySwitch();
        setupTransitionEffect();
        setupMotionMode();
        setupTickingSwitch();
        setupUpdateSource();
        setupUpdateCheck();
        // 电池优化引导（荣耀等机型避免后台被杀）
        maybePromptBattery();
        refreshVersion();
        // 首页 = 库列表（首屏直接落地，不播页面过渡动画）
        applyLibPage();
        maybeShowMetaNotice();
        maybePromptRestore();
        setupSwipeToOpenDrawer();
        // 老版本缩略图（长边 256 的等比图）在两列方格上会被放大 2 倍多发虚：
        // 后台一次性重做成「中心正方形 + 按屏幕取边长」，做完清缓存重绑一次，这次启动就能看到清晰图
        new Thread(() -> {
            WallpaperStore.regenerateThumbs(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                wallpaperAdapter.clearThumbs();
                libAdapter.clearThumbs();
                refreshCurrentPage();
            });
        }, "thumb-regen").start();
    }

    /**
     * 返回键/返回手势的分层处理：抽屉开着先关抽屉 → 有浮出图标先收起 →
     * 壁纸网格页回库列表 → 库列表页才交回系统（退出 App）。
     */
    private void setupBackPressed() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                DrawerLayout drawer = findViewById(R.id.drawer_layout);
                if (drawer != null && drawer.isDrawerOpen(Gravity.START)) {
                    drawer.closeDrawer(Gravity.START);
                    return;
                }
                if (libAdapter.isDeleteMode()) {
                    // 删除态：返回键 = 退出删除态（而不是退 App）
                    exitLibDeleteMode();
                    return;
                }
                if (wallpaperAdapter.hideRevealed()) {
                    return;
                }
                // 正在就地改名：返回 = 取消改名并收起输入法，而不是退出 App
                if (renamingLibId != null) {
                    cancelRename();
                    return;
                }
                if (showingWallpapers) {
                    showLibPage();
                    return;
                }
                // 库列表页：交回系统默认行为（退出 App）。默认动作若只是把任务切到后台、
                // 没销毁本实例（部分 ROM 如此），这个回调必须立刻恢复启用 —— 否则分层
                // 永久失效：下次在壁纸页按返回、或抽屉开着按返回，会跳过「收抽屉 /
                // 回库列表」直接退回桌面（冷启动又正常，所以时好时坏）。
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });
    }

    /** 库列表页（首页）入口：带淡入淡出过渡（首屏在 onCreate 里直接走 applyLibPage，不放动画）。 */
    private void showLibPage() {
        transitionPages(this::applyLibPage);
    }

    /** 真正落地库列表页：☰ 开抽屉 + 固定标题 WallPaper + ＋ 新建库 + 顶栏删除按钮（长按行拖拽排序）。 */
    private void applyLibPage() {
        showingWallpapers = false;
        exitLibDeleteMode();
        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.app_title);
        toolbar.setNavigationIcon(R.drawable.ic_menu);
        toolbar.setNavigationOnClickListener(v -> openDrawer());
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(libAdapter);
        ((Button) findViewById(R.id.btn_add)).setText(R.string.lib_new);
        refreshLibs();
        refreshSlotCards();
        updateLibDeleteToolbar();
    }

    /** 壁纸网格页入口：库已经不在了就不切页。 */
    private void showWallpaperPage(String libId) {
        if (LibraryStore.get(this, libId) == null) {
            return;
        }
        transitionPages(() -> applyWallpaperPage(libId));
    }

    /** 真正落地壁纸网格页：← 返回库列表 + 库名 + ＋ 添加壁纸，两列正方形网格。 */
    private void applyWallpaperPage(String libId) {
        LibraryStore.Library lib = LibraryStore.get(this, libId);
        if (lib == null) {
            return;
        }
        showingWallpapers = true;
        currentLibId = libId;
        // 离开库列表页：改名编辑态随被替换掉的视图一起消失，标记不能留脏值
        renamingLibId = null;
        prefs.edit().putString(LibraryStore.KEY_CURRENT_LIB, libId).apply();
        wallpaperAdapter.hideRevealed();
        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(lib.name);
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationOnClickListener(v -> showLibPage());
        recycler.setLayoutManager(new GridLayoutManager(this, 2));
        recycler.setAdapter(wallpaperAdapter);
        ((Button) findViewById(R.id.btn_add)).setText(R.string.add_wallpaper);
        refreshList();
        refreshSlotCards();
        // 删除按钮是库页专属，进壁纸页要收起来
        updateLibDeleteToolbar();
    }

    /**
     * 页面切换的过渡：槽位卡片带 + 列表带整体淡出 90ms，中途换内容，再淡入 130ms。
     * 连点时先把上一次没落地的换页补跑完（withEndAction 被 cancel 就不执行了，
     * 不管它的话界面会停在半透明或旧页面上）。
     * v3.66 试过水平推移（滑走 40% 屏宽再从对面滑进来），真机用着不合适已回退，方向感待后续重做。
     */
    private void transitionPages(Runnable swap) {
        if (pendingPageSwap != null) {
            Runnable prev = pendingPageSwap;
            pendingPageSwap = null;
            resetPageFade();
            prev.run();
        }
        pendingPageSwap = swap;
        animatePages(0f, 90, () -> {
            Runnable todo = pendingPageSwap;
            pendingPageSwap = null;
            if (todo != null) {
                todo.run();
            }
            animatePages(1f, 130, null);
        });
    }

    /** 两块内容一起淡入淡出；结束回调只挂在列表带上，免得换页动作跑两次。 */
    private void animatePages(float to, long duration, Runnable end) {
        if (slotCards != null) {
            slotCards.animate().alpha(to).setDuration(duration).start();
        }
        View list = findViewById(R.id.list_container);
        if (end == null) {
            list.animate().alpha(to).setDuration(duration).start();
        } else {
            list.animate().alpha(to).setDuration(duration).withEndAction(end).start();
        }
    }

    /** 取消进行中的淡入淡出并把两块拉回不透明（连点时用）。 */
    private void resetPageFade() {
        if (slotCards != null) {
            slotCards.animate().cancel();
            slotCards.setAlpha(1f);
        }
        View list = findViewById(R.id.list_container);
        list.animate().cancel();
        list.setAlpha(1f);
    }

    /** 刷新库列表与空状态。 */
    private void refreshLibs() {
        List<LibraryStore.Library> libs = LibraryStore.load(this);
        libAdapter.setItems(libs);
        updateEmptyState(libs.isEmpty(), R.string.empty_libs, R.string.empty_libs_hint);
    }

    /** 范围槽位滑块接线（一次性）：整页吸附，初始停在桌面那页；翻页后记下当前是哪一面。 */
    private void setupSlotCards() {
        slotCards = findViewById(R.id.slot_cards);
        slotPager = findViewById(R.id.slot_pager);
        LinearLayoutManager lm = new LinearLayoutManager(this,
                LinearLayoutManager.HORIZONTAL, false);
        slotPager.setLayoutManager(lm);
        slotAdapter = new SlotPagerAdapter();
        slotPager.setAdapter(slotAdapter);
        new PagerSnapHelper().attachToRecyclerView(slotPager);
        // 虚拟页数从 0 起排，偶数位 = 桌面；起点选中间那组，左右都能一直翻。
        // 停在哪一面按上次记的来：Activity 会被系统配置变化重建（真机：换锁屏壁纸后重建了两次），
        // 这里硬写桌面就成了「切完锁屏，卡片自己跳回桌面」
        slotPager.scrollToPosition(SLOT_LOOP
                + (prefs.getBoolean(KEY_SLOT_PAGE_HOME, true) ? 0 : 1));
        slotPager.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    syncSlotPage();
                }
            }
        });
        syncSlotPage();
    }

    /** 记下滑块当前停在哪一面、当前卡片视图是谁：拖拽命中与单击/双击都按这一面走。 */
    private void syncSlotPage() {
        if (slotPager == null) {
            return;
        }
        RecyclerView.LayoutManager manager = slotPager.getLayoutManager();
        if (!(manager instanceof LinearLayoutManager)) {
            return;
        }
        int pos = ((LinearLayoutManager) manager).findFirstVisibleItemPosition();
        if (pos < 0) {
            // 还没布局（首次进页/刚重建），保持上次的记录
            return;
        }
        slotPageForHome = pos % 2 == 0;
        if (prefs.getBoolean(KEY_SLOT_PAGE_HOME, true) != slotPageForHome) {
            // 只在换面时写一次（滑动落定才走到这里），不每帧写
            prefs.edit().putBoolean(KEY_SLOT_PAGE_HOME, slotPageForHome).apply();
        }
        RecyclerView.ViewHolder holder = slotPager.findViewHolderForAdapterPosition(pos);
        slotPageCard = holder == null ? null : holder.itemView;
    }

    /**
     * 一页卡片的手势：单击 = 开聚合设置弹窗，双击 = 该范围马上切一张。
     * 两种手势得在这里自己分发：若让卡片照常响应点击，双击的第一下也会把弹窗顶出来。
     * 代价是单击开弹窗要等约 300ms（等系统确认这不是双击），区分单击/双击绕不开。
     * 范围归属读 holder.forHome（回收复用时同一张视图可能改绑另一面）。
     */
    private void attachCardGestures(final SlotPagerAdapter.SlotHolder holder) {
        GestureDetector detector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        showSlotSettingsDialog(holder.forHome);
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        slotSwitchNow(holder.forHome);
                        return true;
                    }
                });
        holder.itemView.setOnTouchListener((v, event) -> {
            // 事件被这里消费后 View 不再自动维护按压态，手动置位否则点下去没有水波纹
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.setPressed(true);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.setPressed(false);
            }
            return detector.onTouchEvent(event);
        });
    }

    /** 卡片右上角感叹号：只弹一句说明，不加大标题（用户嫌标题抢戏）。 */
    private void showSlotGuide() {
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.slot_guide_msg)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    /**
     * 刷新滑块：壁纸网格页收起，按当前槽位重绑挂在屏上的那一两页。
     *
     * 刻意不走 notifyDataSetChanged：滑块有两千个虚拟页，全量刷新会让布局从头排一遍、落点回到第 0 页
     * （偶数页 = 桌面），于是「在锁屏页双击切一张，界面自己滑回桌面」——真机上事后补一句
     * scrollToPosition 也压不住。页与页之间从不增删、也不换数据源，重绑可见页就够了；
     * 滑出去的页回收后再挂回来时照常走 onBindViewHolder。
     */
    private void refreshSlotCards() {
        if (slotCards == null) {
            return;
        }
        slotCards.setVisibility(showingWallpapers ? View.GONE : View.VISIBLE);
        if (showingWallpapers) {
            return;
        }
        syncSlotPage();
        for (int i = 0; i < slotPager.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = slotPager.getChildViewHolder(slotPager.getChildAt(i));
            if (holder instanceof SlotPagerAdapter.SlotHolder) {
                SlotPagerAdapter.SlotHolder slot = (SlotPagerAdapter.SlotHolder) holder;
                bindSlotPage(slot, slot.forHome);
            }
        }
    }

    /** 滑块适配器：真实两页（偶数 = 桌面、奇数 = 锁屏），虚拟页数取模 → 左右无限循环。 */
    private class SlotPagerAdapter extends RecyclerView.Adapter<SlotPagerAdapter.SlotHolder> {

        class SlotHolder extends RecyclerView.ViewHolder {

            final TextView tvScope, tvName, tvDesc;
            final ImageView imgThumb, imgBadge, btnHelp, btnPause;
            /** 这一页现在代表哪个范围（绑定时写入，手势回调读它）。 */
            boolean forHome = true;

            SlotHolder(View item) {
                super(item);
                tvScope = item.findViewById(R.id.tv_slot_scope);
                tvName = item.findViewById(R.id.tv_slot_name);
                tvDesc = item.findViewById(R.id.tv_slot_desc);
                imgThumb = item.findViewById(R.id.img_slot_thumb);
                imgBadge = item.findViewById(R.id.img_slot_badge);
                btnHelp = item.findViewById(R.id.btn_slot_help);
                btnPause = item.findViewById(R.id.btn_slot_pause);
                btnHelp.setOnClickListener(v -> showSlotGuide());
                // 子 View 自己消费点击，卡片那套单击/双击手势收不到落在键上的事件（感叹号同理）
                btnPause.setOnClickListener(v -> toggleSlotPause(forHome));
                attachCardGestures(this);
            }
        }

        @Override
        public SlotHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            return new SlotHolder(LayoutInflater.from(MainActivity.this)
                    .inflate(R.layout.item_slot, parent, false));
        }

        @Override
        public void onBindViewHolder(SlotHolder holder, int position) {
            holder.forHome = position % 2 == 0;
            bindSlotPage(holder, holder.forHome);
        }

        @Override
        public int getItemCount() {
            return SLOT_LOOP * 2;
        }
    }

    /** 绑一页：范围小标题与徽标固定，两行文字与缩略图跟着槽位走，整套颜色跟着挑的色相/浓淡走。 */
    private void bindSlotPage(SlotPagerAdapter.SlotHolder holder, boolean forHome) {
        int placeholder = forHome ? R.drawable.ic_home : R.drawable.ic_lock;
        int hue = SlotTheme.hue(this), tone = SlotTheme.tone(this);
        int bg = SlotTheme.color(hue, tone);
        int dim = SlotTheme.dimText(this, tone);
        MaterialCardView card = (MaterialCardView) holder.itemView;
        card.setCardBackgroundColor(bg);
        card.setStrokeColor(SlotTheme.stroke(hue, tone));
        holder.tvScope.setTextColor(SlotTheme.scopeText(this, tone));
        holder.tvName.setTextColor(SlotTheme.nameText(this, tone));
        holder.tvDesc.setTextColor(dim);
        holder.imgThumb.setBackgroundColor(SlotTheme.thumbBg(this, hue, tone));
        holder.btnHelp.setImageTintList(ColorStateList.valueOf(dim));
        holder.itemView.setAlpha(1f);
        boolean paused = LibraryStore.slotPaused(this, forHome);
        // 暂停中整圆染品牌蓝、图标转白；未暂停是白圆灰图标（深色卡片上白圆照样看得见）
        holder.btnPause.setBackgroundResource(
                paused ? R.drawable.slot_pause_bg_active : R.drawable.slot_pause_bg);
        holder.btnPause.setImageResource(paused ? R.drawable.ic_play : R.drawable.ic_pause);
        holder.btnPause.setImageTintList(getColorStateList(
                paused ? R.color.card_bg : R.color.text_secondary));
        holder.tvScope.setText(forHome ? R.string.slot_home_title : R.string.slot_lock_title);
        holder.imgBadge.setImageResource(placeholder);
        LibraryStore.Library lib = LibraryStore.slotLib(this, forHome);
        if (lib == null) {
            holder.tvName.setText(R.string.slot_none_name);
            holder.tvDesc.setText(R.string.slot_unset);
            bindThumb(holder.imgThumb, null, placeholder, 30, dim);
            return;
        }
        holder.tvName.setText(lib.name);
        holder.tvDesc.setText(slotIntervalDisplay(forHome));
        bindThumb(holder.imgThumb, slotThumbId(lib.id, forHome), placeholder, 30, dim);
    }

    /**
     * 卡片右侧的暂停键：撤掉/重排这一面的周期任务，屏上那张原样停着。
     * WorkManager 的撤与排是进程内 IPC，跟其它设置写点一样放主线程调用。
     */
    private void toggleSlotPause(final boolean forHome) {
        final boolean paused = !LibraryStore.slotPaused(this, forHome);
        TimerScheduler.setPaused(getApplicationContext(), forHome, paused);
        refreshSlotCards();
        refreshTimerStatus();
        if (paused) {
            Toast.makeText(this, R.string.slot_paused_toast, Toast.LENGTH_SHORT).show();
        } else {
            long next = TimerScheduler.scopeTrigger(this, forHome);
            Toast.makeText(this, getString(R.string.slot_resumed_toast,
                    TimerScheduler.clockText(this, next)), Toast.LENGTH_SHORT).show();
        }
    }

    /** 卡片上的「马上切换一张」：槽位没库先提示去卡片设置。 */
    private void slotSwitchNow(boolean forHome) {
        LibraryStore.Library lib = LibraryStore.slotLib(this, forHome);
        if (lib == null) {
            Toast.makeText(this, R.string.slot_none_toast, Toast.LENGTH_SHORT).show();
            return;
        }
        switchAndToast(lib, forHome);
    }

    /** 占位图标用默认次级灰的简写。 */
    private void bindThumb(ImageView view, String wallpaperId, int placeholderRes, int padDp) {
        bindThumb(view, wallpaperId, placeholderRes, padDp, getColor(R.color.text_secondary));
    }

    /**
     * 缩略图位统一绑定：有图清掉占位用的内边距与着色（不清会把真图按 SRC_IN 染成剪影），
     * 没图退回指定线性图标（按 padDp 居中、用 placeholderTint 着色）。
     * 回收复用时两条路径都显式设置，状态才不串。
     */
    private void bindThumb(ImageView view, String wallpaperId, int placeholderRes, int padDp,
                           int placeholderTint) {
        if (view == null) {
            return;
        }
        Bitmap bmp = wallpaperId == null ? null : libAdapter.thumbFor(wallpaperId);
        if (bmp != null) {
            view.setPadding(0, 0, 0, 0);
            view.setImageTintList(null);
            view.setImageBitmap(bmp);
        } else {
            int pad = (int) (padDp * getResources().getDisplayMetrics().density);
            view.setPadding(pad, pad, pad, pad);
            view.setImageTintList(ColorStateList.valueOf(placeholderTint));
            view.setImageResource(placeholderRes);
        }
    }

    /** 某库某范围的代表图：该范围在屏的那张优先，其次另一范围，再次库内第一张；空库 null。 */
    private String slotThumbId(String libId, boolean forHome) {
        String id = Switcher.getCurrent(this, libId, forHome);
        if (id == null) {
            id = Switcher.getCurrent(this, libId, !forHome);
        }
        if (id == null) {
            List<WallpaperStore.Item> items = WallpaperStore.loadByLib(this, libId);
            if (!items.isEmpty()) {
                id = items.get(0).id;
            }
        }
        return id;
    }

    /** 刷新壁纸网格（当前库内的壁纸）与空状态。 */
    private void refreshList() {
        String libId = currentLibId();
        List<WallpaperStore.Item> items = libId == null
                ? new ArrayList<>() : WallpaperStore.loadByLib(this, libId);
        wallpaperAdapter.setItems(items);
        updateEmptyState(items.isEmpty(), R.string.empty_wallpapers, R.string.empty_wallpapers_hint);
    }

    /** 空状态显隐与文案（两页共用同一块视图）。 */
    private void updateEmptyState(boolean empty, int titleRes, int hintRes) {
        View emptyView = findViewById(R.id.empty_state);
        if (emptyView == null) {
            return;
        }
        ((TextView) findViewById(R.id.tv_empty_title)).setText(titleRes);
        ((TextView) findViewById(R.id.tv_empty_hint)).setText(hintRes);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    /** 当前库 id（未初始化时从持久化恢复；指的是壁纸页正在看的库）。 */
    private String currentLibId() {
        if (currentLibId == null) {
            currentLibId = prefs.getString(LibraryStore.KEY_CURRENT_LIB, null);
        }
        return currentLibId;
    }

    /** 壁纸页正在看的库，可能为 null。 */
    private LibraryStore.Library currentLib() {
        String id = currentLibId();
        if (id == null) {
            return null;
        }
        return LibraryStore.get(this, id);
    }


    @Override
    protected void onResume() {
        super.onResume();
        // 自愈：每次回到应用都核对一遍排定（槽位配置没变时走 KEEP，不碰系统里已有的周期格子，
        // 因此不会像以前每次 UPDATE 那样把自动切换整轮往后推）
        TimerScheduler.scheduleAll(this);
        // 打开应用同样是“设备活跃”时机：后台补跑被 Doze/ROM 冻结而漏掉的定时切换
        new Thread(() -> {
            final boolean did = TimerScheduler.catchUp(getApplicationContext());
            if (did) {
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        refreshTimerStatus();
                        refreshCurrentPage();
                    }
                });
            }
        }, "app-catchup").start();
        // 同步刷新桌面小组件（库的启用状态、当前壁纸可能已变化）
        WidgetProvider.updateWidget(this);
        // 通知不跨重启存活、也可能被 ROM 清掉：回到应用时兜底补发/刷新常驻通知
        StatusNotifier.update(this);
        // 从系统选择器返回：用户没确认就把接管意图关掉（开关自动回关）
        refreshTakeoverUi();
        // 从安装页/安装授权页返回：更新那一行按当前下载状态重刷（可能已经下完待装，或包已装上）
        UpdateDownloader.pruneInstalled(this);
        bindUpdateState();
        List<String> pending = WallpaperStore.pendingInbox(this);
        if (!pending.isEmpty()) {
            // 还有待编辑项：继续逐张处理（目标库为空时编辑页自己回退到第一个库）
            openEdit(pending.get(0), currentLibId());
            return;
        }
        // 可能在裁剪页覆盖过图：缩略图缓存清掉再刷，避免显示旧图
        wallpaperAdapter.clearThumbs();
        libAdapter.clearThumbs();
        refreshCurrentPage();
    }

    /** 按当前所在页刷新：壁纸页先看库还在不在（可能已被长按删掉）。 */
    private void refreshCurrentPage() {
        if (showingWallpapers) {
            LibraryStore.Library lib = currentLib();
            if (lib == null) {
                showLibPage();
                return;
            }
            ((Toolbar) findViewById(R.id.toolbar)).setTitle(lib.name);
            refreshList();
        } else {
            refreshLibs();
        }
    }

    /** 左侧设置抽屉：顶栏 ☰ 打开（showLibPage 里绑定），右滑手势由 DrawerLayout 自带。 */
    private void setupDrawer() {
        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        if (drawer == null) {
            return;
        }
        // 抽屉每次打开都同步一次全局设置状态
        drawer.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                refreshTimerStatus();
                refreshSlotCards();
                refreshBatteryRow();
                refreshLauncherOverlayRow();
                refreshExportRows();
                refreshBackupRow();
                refreshSwitchLogRow();
                syncTakeoverAsync();
            }
        });
        // 「桌面图标预览底图」：未设置时点按直接选图；已设置时点按浮出查看/更换/清除，长按仍为快捷清除
        View overlayRow = findViewById(R.id.row_launcher_overlay);
        if (overlayRow != null) {
            overlayRow.setOnClickListener(v -> {
                if (LauncherPreviewOverlay.overlayFile(this).exists()) {
                    showLauncherOverlayMenu();
                } else {
                    pickLauncherOverlay();
                }
            });
            overlayRow.setOnLongClickListener(v -> {
                confirmClearLauncherOverlay();
                return true;
            });
        }
        // 导出目录：点按选/换一个文件夹（SAF），长按取消；下一行是「立即导出全部壁纸」
        View exportDirRow = findViewById(R.id.row_export_dir);
        if (exportDirRow != null) {
            exportDirRow.setOnClickListener(v -> exportDirLauncher.launch(null));
            exportDirRow.setOnLongClickListener(v -> {
                confirmClearExportDir();
                return true;
            });
        }
        View exportNowRow = findViewById(R.id.row_export_now);
        if (exportNowRow != null) {
            exportNowRow.setOnClickListener(v -> exportAllWallpapers());
        }
        // 备份整库（写一个 zip 到导出目录）；下一行从包还原
        View backupRow = findViewById(R.id.row_backup);
        if (backupRow != null) {
            backupRow.setOnClickListener(v -> startBackup());
        }
        View restoreRow = findViewById(R.id.row_restore);
        if (restoreRow != null) {
            restoreRow.setOnClickListener(v -> restoreZipLauncher.launch(new String[]{"application/zip", "*/*"}));
        }
        // 保存当前壁纸：接管中导出引擎正在显示的那张；未接管备份系统壁纸（并记为可还原存档）
        View saveWallpaperRow = findViewById(R.id.row_save_wallpaper);
        if (saveWallpaperRow != null) {
            saveWallpaperRow.setOnClickListener(v -> startSaveCurrentWallpaper());
        }
        // 切换日志：点开直接读私有目录那个文件弹窗展示（不必先设导出目录再去文件管理器翻）；
        // 长按清空（老版本写的记录是旧格式，换新格式后一般想清掉重记）
        View logRow = findViewById(R.id.row_switch_log);
        if (logRow != null) {
            logRow.setOnClickListener(v -> showSwitchLog());
            logRow.setOnLongClickListener(v -> {
                confirmClearSwitchLog();
                return true;
            });
        }
    }

    /** 打开左侧设置抽屉（☰ 按钮、右滑手势与「去开接管」提示共用）。 */
    private void openDrawer() {
        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        if (drawer != null) {
            drawer.openDrawer(Gravity.START);
        }
    }

    /**
     * 首页（库列表）整页右滑打开抽屉。
     * 光靠 DrawerLayout 自带的边缘手势在全面屏上很难用：屏幕左边缘那条已经被系统「返回」手势占了
     * （系统手势优先），手指稍往中间一点就拉不出抽屉，还会被当成点击进库。
     * 这里只**观察**手势（从不拦截），横向为主、方向向右、距离够就开抽屉；竖向滑动完全交给列表滚动。
     */
    private void setupSwipeToOpenDrawer() {
        final float threshold = 48 * getResources().getDisplayMetrics().density;
        recycler.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            private float downX;
            private float downY;

            @Override
            public boolean onInterceptTouchEvent(RecyclerView view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        break;
                    case MotionEvent.ACTION_UP:
                        float dx = event.getX() - downX;
                        float dy = event.getY() - downY;
                        // 只在库列表页生效：壁纸页右滑的直觉是「回上一级」，不抢那个手势
                        if (!showingWallpapers && dx > threshold && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                            openDrawer();
                        }
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    /** 版本号行：顶栏标题固定为 WallPaper，版本挪到抽屉顶部以便确认手机上装的是哪一版。 */
    private void refreshVersion() {
        TextView version = findViewById(R.id.tv_version);
        if (version == null) {
            return;
        }
        String name = null;
        try {
            name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        if (name != null && !name.isEmpty()) {
            version.setText(getString(R.string.title_with_version, getString(R.string.app_name), name));
        }
    }

    /**
     * 元数据文件出过事时如实告诉用户（MetaFiles 在读 library.json / libraries.json 时登记）。
     * 救回来的一律自动写回；整份读不动的原样留在原地并另存留档，
     * 让用户把留档拷走手工捡 id/标题，而不是静默显示「库是空的」。
     */
    private void maybeShowMetaNotice() {
        final MetaFiles.Notice notice = MetaFiles.takeNotice();
        if (notice == null) {
            return;
        }
        String dir = getFilesDir().getAbsolutePath();
        String msg;
        if (notice.salvaged) {
            msg = getString(R.string.meta_notice_salvaged, notice.fileName, dir + "/" + notice.fileName,
                    notice.kept, notice.dropped,
                    notice.archivePath == null ? getString(R.string.meta_archive_failed) : notice.archivePath);
        } else {
            msg = getString(R.string.meta_notice_lost, notice.fileName, dir + "/" + notice.fileName,
                    notice.archivePath == null ? getString(R.string.meta_archive_failed) : notice.archivePath);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.meta_notice_title)
                .setMessage(msg)
                .setPositiveButton(R.string.confirm, null)
                .show();
    }

    /**
     * 看起来像新装机（没写过库文件、库与壁纸都是空的）就问一句要不要从备份包还原。
     * 只问一次（markPromptShown 落本机 key，且这个 key 不参与还原）；
     * 抽屉里那一行「从备份包还原」永久留着，什么时候想还原都可以。
     */
    private void maybePromptRestore() {
        if (BackupStore.promptShown(this) || !BackupStore.looksLikeFreshInstall(this)) {
            return;
        }
        BackupStore.markPromptShown(this);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.restore_ask_title)
                .setMessage(R.string.restore_ask_msg)
                .setPositiveButton(R.string.restore_ask_go, (dialog, which) ->
                        restoreZipLauncher.launch(new String[]{"application/zip", "*/*"}))
                .setNegativeButton(R.string.restore_ask_no, null)
                .show();
    }

    /** 右下角 ＋（库页 = 新建库 / 壁纸页 = 添加壁纸）与电池行、接管开关。 */
    private void setupButtons() {
        findViewById(R.id.btn_add).setOnClickListener(v -> {
            if (showingWallpapers) {
                launchPicker();
            } else {
                showNewLibDialog();
            }
        });
        View batteryRow = findViewById(R.id.row_battery);
        if (batteryRow != null) {
            batteryRow.setOnClickListener(v -> requestIgnoreBattery());
        }
        // 顶部横幅的「启用」：拉起系统动态壁纸选择器（与 Muzei 的启用入口一致）
        View activate = findViewById(R.id.btn_activate);
        if (activate != null) {
            activate.setOnClickListener(v -> enableTakeover());
        }
        refreshTakeoverUi();
        refreshBatteryRow();
    }


    /**
     * 到点通知开关：自动切换（定时与补切）完成后发系统通知——成功静音留痕、失败弹横幅。
     * 打开时先确保通知可用，否则开关开了也看不到任何提示。
     */
    private void setupNotifySwitch() {
        CompoundButton swNotify = findViewById(R.id.sw_auto_notify);
        swNotify.setOnCheckedChangeListener(null);
        swNotify.setChecked(SwitchNotifier.isEnabled(this));
        swNotify.setOnCheckedChangeListener((buttonView, isChecked) -> {
            SwitchNotifier.setEnabled(MainActivity.this, isChecked);
            if (isChecked) {
                ensureNotificationsEnabled();
            }
        });
        // 默认开启且尚未授权时，首次打开应用请求一次通知权限（已请求过就不再打扰）
        if (SwitchNotifier.isEnabled(this) && !prefs.getBoolean(KEY_NOTIFY_PROMPTED, false)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean(KEY_NOTIFY_PROMPTED, true).apply();
            notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    /**
     * 常驻切换通知开关：通知栏按范围常驻「音乐播放器样式」的状态通知 —— 桌面与锁屏各一条
     * （封面 + 壁纸标题 + 范围/库名/模式/间隔 + 下次切换时间 + 上一张/暂停·继续/下一张）。
     * 一个开关管两条；只想关其中一条，去系统通知设置里按渠道关（渠道名：桌面切换状态 / 锁屏切换状态）。
     * 打开时同样先确保通知可用；打开后立刻按当前状态补发。
     */
    private void setupStatusNotifySwitch() {
        CompoundButton swStatus = findViewById(R.id.sw_status_notify);
        swStatus.setOnCheckedChangeListener(null);
        swStatus.setChecked(StatusNotifier.isEnabled(this));
        swStatus.setOnCheckedChangeListener((buttonView, isChecked) -> {
            StatusNotifier.setEnabled(MainActivity.this, isChecked);
            if (isChecked) {
                ensureNotificationsEnabled();
                StatusNotifier.update(this);
            }
        });
    }

    /**
     * 切换动画：引擎模式换壁纸时的过渡效果（淡入/模糊过渡/无动画），点行弹出单选。
     * 值存 settings（与引擎共用 {@link WallSwitchService#KEY_TRANSITION}），即时生效。
     */
    private void setupTransitionEffect() {
        LinearLayout row = findViewById(R.id.row_transition);
        TextView tv = findViewById(R.id.tv_transition_effect);
        String[] values = {
                WallSwitchService.TRANSITION_FADE,
                WallSwitchService.TRANSITION_BLUR,
                WallSwitchService.TRANSITION_OFF
        };
        String[] labels = {
                getString(R.string.transition_fade),
                getString(R.string.transition_blur),
                getString(R.string.transition_off)
        };
        Runnable refresh = () -> {
            String cur = WallSwitchService.transitionEffect(this);
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(cur)) {
                    tv.setText(labels[i]);
                    return;
                }
            }
            tv.setText(labels[0]);
        };
        refresh.run();
        row.setOnClickListener(v -> {
            String cur = WallSwitchService.transitionEffect(this);
            int checked = WallSwitchService.TRANSITION_OFF.equals(cur) ? 2
                    : WallSwitchService.TRANSITION_BLUR.equals(cur) ? 1 : 0;
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.transition_effect_title)
                    .setSingleChoiceItems(labels, checked, (d, which) -> {
                        prefs.edit().putString(WallSwitchService.KEY_TRANSITION, values[which]).apply();
                        refresh.run();
                        d.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    /**
     * 实况播放方式：桌面那张动态照片怎么个动法（按住 / 循环），点行弹出单选。
     *
     * <p>值存 settings（与引擎共用 {@link WallSwitchService#KEY_MOTION_MODE}），改档即时生效：
     * 引擎每次事件都重读，不用重启壁纸进程。默认按住播放 —— 循环档是可见期间 60 帧/秒常驻，
     * 得用户自己显式选。
     */
    private void setupMotionMode() {
        LinearLayout row = findViewById(R.id.row_motion);
        TextView tv = findViewById(R.id.tv_motion_mode);
        String[] values = {
                WallSwitchService.MOTION_HOLD,
                WallSwitchService.MOTION_LOOP
        };
        String[] labels = {getString(R.string.motion_hold), getString(R.string.motion_loop)};
        Runnable refresh = () -> {
            String cur = WallSwitchService.motionMode(this);
            if (!WallSwitchService.MOTION_LOOP.equals(cur)) {
                tv.setText(R.string.motion_hold);
                return;
            }
            // 循环档把间隔一起显示出来：只写「循环播放」的话，改完间隔界面看不出有没有生效
            long gap = WallSwitchService.motionLoopGapMs(this);
            tv.setText(gap <= 0 ? getString(R.string.motion_loop_no_gap)
                    : getString(R.string.motion_loop_with_gap, gapLabel(gap)));
        };
        refresh.run();
        row.setOnClickListener(v -> {
            String before = WallSwitchService.motionMode(this);
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.motion_mode_title)
                    .setSingleChoiceItems(labels,
                            WallSwitchService.MOTION_LOOP.equals(before) ? 1 : 0,
                            (d, which) -> {
                                d.dismiss();
                                if (!WallSwitchService.MOTION_LOOP.equals(values[which])) {
                                    applyMotionMode(values[which]);
                                    return;
                                }
                                // 选了循环就紧接着问间隔；这一步取消则整件事不生效（档也不切），
                                // 免得留下「切到循环但从来没设过间隔」的半套状态
                                askMotionLoopGap(before);
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    /**
     * 循环档的每轮间隔：0~30 秒滚轮，样式照「自动切换间隔」那个滚轮（同一个 {@link #wheelColumn}）。
     * 取消或返回 = 保持原来那一档，不切到循环。
     *
     * <p>为什么不是单选列表：v3.89 第一版是「标题 + 一行说明 + 七个单选项」，装机实测**选项整块没了**
     * —— appcompat 的弹窗在有 message 时把列表塞进 customPanel，量出来是零高（本仓库另外五个
     * 单选框都只带标题不带说明，唯一带说明的就是这个坏的）。滚轮这条路是这仓库里验证过的样式，
     * 而且「秒数」本来就该是个数，不是七个档位。
     */
    private void askMotionLoopGap(String beforeMode) {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(pad, pad / 2, pad, 0);
        // 只认整秒：老值（比如 500ms）落回 0~30 的整数格，下一次确定时就归成整秒
        int current = (int) Math.min(30L, Math.max(0L,
                WallSwitchService.motionLoopGapMs(this) / 1000L));
        final NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(0);
        picker.setMaxValue(30);
        picker.setWrapSelectorWheel(false);
        picker.setValue(current);
        row.addView(wheelColumn(picker, getString(R.string.motion_gap_unit)));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.motion_gap_title)
                .setView(row)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    prefs.edit().putLong(WallSwitchService.KEY_MOTION_LOOP_GAP,
                            picker.getValue() * 1000L).apply();
                    applyMotionMode(WallSwitchService.MOTION_LOOP);
                })
                .setNegativeButton(R.string.cancel, (d, which) -> applyMotionMode(beforeMode))
                .setOnCancelListener(d -> applyMotionMode(beforeMode))
                .show();
    }

    /** 写档 + 刷界面 + 立刻通知引擎（三处入口共用，别各写一遍）。 */
    private void applyMotionMode(String mode) {
        prefs.edit().putString(WallSwitchService.KEY_MOTION_MODE, mode).apply();
        TextView tv = findViewById(R.id.tv_motion_mode);
        if (WallSwitchService.MOTION_LOOP.equals(mode)) {
            long gap = WallSwitchService.motionLoopGapMs(this);
            tv.setText(gap <= 0 ? getString(R.string.motion_loop_no_gap)
                    : getString(R.string.motion_loop_with_gap, gapLabel(gap)));
        } else {
            tv.setText(R.string.motion_hold);
        }
        WallSwitchService.notifyMotionModeChanged();
    }

    /** 毫秒说成人话：整秒不带小数，半秒带；0 由调用方单独处理。 */
    private String gapLabel(long ms) {
        if (ms % 1000L == 0) {
            return String.valueOf(ms / 1000L);
        }
        return String.valueOf(ms / 1000f);
    }

    /**
     * 更新源选择：设置页点「更新源」弹单选（Gitee 国内直连 / GitHub 需代理），存 prefs。
     * 检查更新与下载都按所选源走对应仓库的滚动 Release。
     */
    private void setupUpdateSource() {
        TextView tvSource = findViewById(R.id.tv_update_source);
        Runnable refresh = () -> tvSource.setText(
                UpdateChecker.SRC_GITHUB.equals(UpdateChecker.source(this))
                        ? R.string.update_source_github : R.string.update_source_gitee);
        refresh.run();
        findViewById(R.id.row_update_source).setOnClickListener(v -> {
            // 下载进行中锁源：不然下一半切了源，剩下的半截包属于另一座仓库，只能作废重下
            if (UpdateDownloader.state(this) == UpdateDownloader.DOWNLOADING) {
                Toast.makeText(this, R.string.update_source_busy, Toast.LENGTH_SHORT).show();
                return;
            }
            String cur = UpdateChecker.source(this);
            String[] values = {UpdateChecker.SRC_GITEE, UpdateChecker.SRC_GITHUB};
            String[] labels = {getString(R.string.update_source_gitee),
                    getString(R.string.update_source_github)};
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.update_source_title)
                    .setSingleChoiceItems(labels,
                            UpdateChecker.SRC_GITHUB.equals(cur) ? 1 : 0,
                            (d, which) -> {
                                UpdateChecker.setSource(this, values[which]);
                                refresh.run();
                                d.dismiss();
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    /**
     * 检查更新：设置页手动入口 + 启动时静默检查（有新版才弹窗，失败不打扰）。
     * 下载走 App 内链路（{@link UpdateDownloader}）：进度条与「点按安装」都收在这一行上，
     * 不再挂系统下载器的通知——那条通知一被划掉，安装包就找不回来了。
     */
    private void setupUpdateCheck() {
        tvUpdateState = findViewById(R.id.tv_update_state);
        pbUpdate = findViewById(R.id.pb_update);
        btnCancelDownload = findViewById(R.id.btn_update_cancel);
        findViewById(R.id.row_update).setOnClickListener(v -> onUpdateRowTapped());
        btnCancelDownload.setOnClickListener(v -> {
            UpdateDownloader.cancel();
            // 目标一并忘掉：取消后再点这一行是重新检查，而不是照原目标续下
            lastUpdateSource = lastUpdateVersion = null;
            bindUpdateState();
        });
        // 上回下的包已经装上了就清账，别让这一行一直挂着「点按安装」
        UpdateDownloader.pruneInstalled(this);
        bindUpdateState();
        // 启动静默检查：只在发现新版时打扰，失败静默（走当前所选源；正下着不打断）
        UpdateChecker.checkAsync(this, (info, error) -> {
            if (error == null && info != null && !isFinishing() && !isDestroyed()
                    && UpdateDownloader.state(this) != UpdateDownloader.DOWNLOADING
                    && UpdateChecker.isNewer(info.version, UpdateChecker.localVersion(this))) {
                showUpdateDialog(info, UpdateChecker.source(this));
            }
        });
    }

    /** 这一行点下去干什么，完全由下载状态决定：下载中不理、有包就装、其余去检查。 */
    private void onUpdateRowTapped() {
        int state = UpdateDownloader.state(this);
        if (state == UpdateDownloader.DOWNLOADING) {
            return;
        }
        if (state == UpdateDownloader.READY) {
            installReadyPackage();
            return;
        }
        if (state == UpdateDownloader.FAILED && lastUpdateVersion != null) {
            // 上回下到一半失败：直接按原目标重下，不用再走一遍检查
            startUpdateDownload(lastUpdateSource, lastUpdateVersion);
            return;
        }
        checkUpdateNow();
    }

    /** 拿本机那个已下好的包装一遍：没授权先跳授权页并说明，包被系统清理了就刷回「检查更新」。 */
    private void installReadyPackage() {
        if (!UpdateDownloader.hasInstallPermission(this)) {
            UpdateDownloader.openInstallPermissionSettings(this);
            Toast.makeText(this, R.string.update_need_install_perm, Toast.LENGTH_LONG).show();
            return;
        }
        if (!UpdateDownloader.install(this)) {
            Toast.makeText(this, R.string.update_pkg_gone, Toast.LENGTH_SHORT).show();
            bindUpdateState();
        }
    }

    /**
     * 下载完成后的提示窗：点「立即安装」走与那一行完全相同的安装链路，
     * 点「稍后安装」或按返回都只是关掉——包还在本机，之后回抽屉点那一行随时能装。
     */
    private void showInstallDialog() {
        String version = UpdateDownloader.readyVersion(this);
        if (version == null) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_done_title)
                .setMessage(getString(R.string.update_done_msg, version))
                .setPositiveButton(R.string.update_install_now,
                        (d, which) -> installReadyPackage())
                .setNegativeButton(R.string.update_install_later, null)
                .show();
    }

    /** 手动检查：结果只改这一行的文字，发现新版才弹窗。 */
    private void checkUpdateNow() {
        String source = UpdateChecker.source(this);
        tvUpdateState.setText(R.string.update_checking);
        UpdateChecker.checkAsync(this, source, (info, error) -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (error != null || info == null) {
                tvUpdateState.setText(R.string.update_check_failed);
                // 把具体死因亮给用户（网络不通/HTTP 错误一眼可辨），不再只有干巴巴的失败
                Toast.makeText(this, getString(R.string.update_check_failed)
                        + "：" + (error != null ? error.toString() : "无返回"), Toast.LENGTH_LONG).show();
                return;
            }
            if (!UpdateChecker.isNewer(info.version, UpdateChecker.localVersion(this))) {
                tvUpdateState.setText(R.string.update_latest);
                return;
            }
            tvUpdateState.setText(getString(R.string.update_new_title) + " v" + info.version);
            showUpdateDialog(info, source);
        });
    }

    /**
     * 新版弹窗。本机已经有这个包了（上回下完没装，重进 App 又查到新版）时主按钮直接给
     * 「立即安装」，并把文案对上本机那个包的版本；否则确认后开始下载。
     */
    private void showUpdateDialog(UpdateChecker.Info info, String source) {
        String ready = UpdateDownloader.readyVersion(this);
        // 本机包不比远端那一版旧才走安装，免得文案写 v3.75、装进去的是旧的 v3.74
        boolean installFirst = ready != null && !UpdateChecker.isNewer(info.version, ready);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_new_title)
                .setMessage(getString(installFirst
                                ? R.string.update_new_ready_msg : R.string.update_new_msg,
                        installFirst ? ready : info.version, UpdateChecker.localVersion(this)))
                .setPositiveButton(installFirst ? R.string.update_install_now
                                : R.string.update_download,
                        (d, which) -> {
                            if (installFirst) {
                                installReadyPackage();
                            } else {
                                startUpdateDownload(source, info.version);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 记下目标版本后开下：进度条马上转起来，失败时这一行也认得该重下哪个版本。 */
    private void startUpdateDownload(String source, String version) {
        lastUpdateSource = source;
        lastUpdateVersion = version;
        UpdateDownloader.start(this, source, version);
        bindUpdateState();
    }

    /** 按下载状态刷这一行；只有下载中才留一个 300ms 的轮询，其余状态立刻停手（不白耗）。 */
    private void bindUpdateState() {
        int state = UpdateDownloader.state(this);
        updateTick.removeCallbacks(updatePoller);
        if (state == UpdateDownloader.DOWNLOADING) {
            pbUpdate.setVisibility(View.VISIBLE);
            btnCancelDownload.setVisibility(View.VISIBLE);
            pbUpdate.setProgress(UpdateDownloader.percent());
            tvUpdateState.setText(getString(R.string.update_downloading,
                    UpdateDownloader.percent()));
            updateTick.postDelayed(updatePoller, 300);
            return;
        }
        pbUpdate.setVisibility(View.GONE);
        btnCancelDownload.setVisibility(View.GONE);
        if (state == UpdateDownloader.READY) {
            tvUpdateState.setText(getString(R.string.update_ready,
                    UpdateDownloader.readyVersion(this)));
        } else if (state == UpdateDownloader.FAILED) {
            tvUpdateState.setText(R.string.update_failed);
        } else {
            tvUpdateState.setText(getString(R.string.update_local_version,
                    UpdateChecker.localVersion(this)));
        }
    }

    /** 下载进度轮询：状态一离开下载中就收尾刷行；下完弹小窗问要不要立刻装，失败只 Toast。 */
    private final Runnable updatePoller = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            int state = UpdateDownloader.state(MainActivity.this);
            if (state == UpdateDownloader.DOWNLOADING) {
                bindUpdateState();
                return;
            }
            bindUpdateState();
            if (state == UpdateDownloader.READY) {
                showInstallDialog();
            } else if (state == UpdateDownloader.FAILED) {
                Toast.makeText(MainActivity.this, R.string.update_failed,
                        Toast.LENGTH_SHORT).show();
            }
        }
    };

    /**
     * 走秒倒计时开关：控制常驻通知与小组件里的时间显示样式（走秒 / 静态）。
     * 三处（本开关、通知、小组件）都走 {@link TimerScheduler#tickingCountdown} 同一个存取器，
     * 默认值也只有一处定义，避免出现"开关显示关、实际却在走秒"这类不一致。
     */
    private void setupTickingSwitch() {
        CompoundButton sw = findViewById(R.id.sw_ticking);
        if (sw == null) {
            return;
        }
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(TimerScheduler.tickingCountdown(this));
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            TimerScheduler.setTickingCountdown(MainActivity.this, isChecked);
            // 立即按新样式重建两处显示，免得"设置改了、通知/小组件还是旧样式"
            StatusNotifier.update(this);
            WidgetProvider.updateWidget(this);
        });
    }

    /** 确保通知可用：Android 13+ 申请权限；系统级关闭时提示并跳到通知设置页。 */
    private void ensureNotificationsEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        if (!SwitchNotifier.canNotify(this)) {
            Toast.makeText(this, R.string.notify_disabled_hint, Toast.LENGTH_LONG).show();
            openNotificationSettings();
        }
    }

    /** 通知权限申请结果：未授权时说明后果（自动切换提示将看不到）。 */
    private void onNotifyPermissionResult(boolean granted) {
        if (!granted) {
            Toast.makeText(this, R.string.notify_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    /** 存档权限申请结果：授权就继续保存当前壁纸，拒绝则放弃。 */
    private void onSavePermissionResult(boolean granted) {
        if (granted) {
            doSaveCurrentWallpaper();
        } else {
            Toast.makeText(this, "未授权读取壁纸，无法备份当前壁纸，已取消开启接管", Toast.LENGTH_LONG).show();
            refreshTakeoverUi();
        }
    }

    /** 跳转本应用的系统通知设置页（华为/荣耀在此重新允许通知）。 */
    private void openNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    /** 新建库弹窗（库列表页右下角 ＋）。 */
    private void showNewLibDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.lib_new)
                .setView(wrapInput(input, R.string.lib_name_hint))
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    LibraryStore.create(MainActivity.this, name);
                    refreshLibs();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 顶栏删除按钮：只给库页用（壁纸页隐藏）。普通态是灰色垃圾桶，点一下进删除态；
     * 删除态变勾图标，点击 = 对勾选项二次确认。菜单用代码加（本地类型检查的 R 桩不含 menu 资源）。
     */
    private void setupLibDeleteMenu() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        libDeleteItem = toolbar.getMenu().add(0, 0, 0, R.string.lib_delete_mode_title);
        libDeleteItem.setIcon(R.drawable.ic_delete);
        libDeleteItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        libDeleteItem.setVisible(false);
        libDeleteItem.setOnMenuItemClickListener(item -> {
            if (libAdapter.isDeleteMode()) {
                confirmDeleteSelectedLibs();
            } else {
                enterLibDeleteMode();
            }
            return true;
        });
    }

    /**
     * 库行拖拽（v3.61 起分两种起手，都由调用方手动 startDrag，不用 ItemTouchHelper 的自动长按）：
     * 1) 长按行的空白/文字区 = 排序，落位后把新顺序写回 libraries.json；
     * 2) 长按行的头像 = 拖出半透明浮动头像，松手落在哪张范围卡片上就把该库设成那个范围。
     * 分工靠「谁是这次的触摸目标」实现：头像自己吃掉 DOWN，长按就只触发它的回调，不会连带触发行排序。
     */
    private void setupLibDrag() {
        libDragHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean isLongPressDragEnabled() {
                // 关掉自动长按：排序改由库行的长按回调手动 startDrag，头像那条长按走浮动头像
                return false;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                if (showingWallpapers) {
                    return false;
                }
                return libAdapter.move(from.getBindingAdapterPosition(), to.getBindingAdapterPosition());
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                if (!showingWallpapers) {
                    LibraryStore.reorder(MainActivity.this, libAdapter.currentIds());
                }
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }
        });
        libDragHelper.attachToRecyclerView(recycler);
    }

    /**
     * 长按头像起手：把头像复制成一个半透明浮动图钉在内容层上，跟着手指走；
     * 原头像压暗表示「被拿起来了」，命中的范围卡片降透明度表示「松手就给我」。
     */
    private void startAvatarDrag(LibHolder holder, String libId) {
        Drawable icon = holder.imgThumb.getDrawable();
        if (icon == null || libId == null) {
            return;
        }
        ViewGroup content = findViewById(android.R.id.content);
        int[] thumbLoc = new int[2];
        int[] contentLoc = new int[2];
        holder.imgThumb.getLocationInWindow(thumbLoc);
        content.getLocationInWindow(contentLoc);
        avatarContentLoc = contentLoc;
        avatarOrigin = holder.imgThumb;
        avatarOrigin.setAlpha(0.3f);
        avatarDragLibId = libId;
        avatarGrabX = avatarLastRawX - thumbLoc[0];
        avatarGrabY = avatarLastRawY - thumbLoc[1];
        ImageView floatView = new ImageView(this);
        floatView.setImageDrawable(icon);
        floatView.setAlpha(0.75f);
        content.addView(floatView, new ViewGroup.LayoutParams(
                holder.imgThumb.getWidth(), holder.imgThumb.getHeight()));
        floatView.setX(thumbLoc[0] - contentLoc[0]);
        floatView.setY(thumbLoc[1] - contentLoc[1]);
        avatarFloat = floatView;
        cacheSlotCardRects();
        // 拖拽期间别让列表抢走手势：否则手指一动就是滚列表，浮动头像会断流
        recycler.requestDisallowInterceptTouchEvent(true);
    }

    /** 浮动头像跟随手指（只在拖起来之后调用）。 */
    private void moveAvatarFloat(float rawX, float rawY) {
        avatarFloat.setX(rawX - avatarContentLoc[0] - avatarGrabX);
        avatarFloat.setY(rawY - avatarContentLoc[1] - avatarGrabY);
        highlightSlotCard(slotCardAt(rawX, rawY));
    }

    /** 松手收尾：命中卡片就走二次确认，然后清掉浮动头像与各种临时状态。 */
    private void finishAvatarDrag(boolean commit, float rawX, float rawY) {
        String libId = avatarDragLibId;
        View card = commit ? slotCardAt(rawX, rawY) : null;
        if (avatarFloat != null) {
            ((ViewGroup) avatarFloat.getParent()).removeView(avatarFloat);
            avatarFloat = null;
        }
        if (avatarOrigin != null) {
            avatarOrigin.setAlpha(1f);
            avatarOrigin = null;
        }
        avatarDragLibId = null;
        highlightSlotCard(null);
        recycler.requestDisallowInterceptTouchEvent(false);
        if (card == null || libId == null) {
            return;
        }
        boolean forHome = slotPageForHome;
        if (LibraryStore.ownsScope(this, libId, forHome)) {
            Toast.makeText(this, R.string.slot_already_set, Toast.LENGTH_SHORT).show();
            return;
        }
        confirmSlotLib(forHome, libId, null);
    }

    /** 拖起来时缓存当前那一页卡片的窗口矩形，之后每帧只比数值，不再逐事件查位置。 */
    private void cacheSlotCardRects() {
        syncSlotPage();
        slotRect = cardWindowRect(slotPageCard);
    }

    private int[] cardWindowRect(View card) {
        if (card == null || card.getVisibility() != View.VISIBLE) {
            return null;
        }
        int[] loc = new int[2];
        card.getLocationInWindow(loc);
        return new int[]{loc[0], loc[1], loc[0] + card.getWidth(), loc[1] + card.getHeight()};
    }

    /** 手指落点在不在当前那一页卡片上（浮动头像松手时用；另一面在屏外，不参与命中）。 */
    private View slotCardAt(float rawX, float rawY) {
        int[] r = slotRect;
        if (r == null || rawX < r[0] || rawX > r[2] || rawY < r[1] || rawY > r[3]) {
            return null;
        }
        return slotPageCard;
    }

    /** 拖到卡片上时给该卡片半透明高亮（只在目标变化时改属性，别每个 MOVE 事件都刷一遍）。 */
    private void highlightSlotCard(View card) {
        if (highlightedSlotCard == card) {
            return;
        }
        if (highlightedSlotCard != null) {
            highlightedSlotCard.setAlpha(1f);
        }
        highlightedSlotCard = card;
        if (card != null) {
            card.setAlpha(0.6f);
        }
    }

    private void enterLibDeleteMode() {
        libAdapter.setDeleteMode(true);
        findViewById(R.id.btn_add).setVisibility(View.GONE);
        updateLibDeleteToolbar();
    }

    private void exitLibDeleteMode() {
        if (!libAdapter.isDeleteMode()) {
            return;
        }
        libAdapter.setDeleteMode(false);
        findViewById(R.id.btn_add).setVisibility(View.VISIBLE);
        updateLibDeleteToolbar();
    }

    /** 顶栏随页面/删除态刷新：壁纸页隐藏按钮；删除态换勾图标，标题显示已选数。 */
    private void updateLibDeleteToolbar() {
        if (libDeleteItem == null) {
            return;
        }
        Toolbar toolbar = findViewById(R.id.toolbar);
        boolean deleteMode = libAdapter.isDeleteMode();
        libDeleteItem.setVisible(!showingWallpapers);
        libDeleteItem.setIcon(deleteMode ? R.drawable.ic_check : R.drawable.ic_delete);
        libDeleteItem.setTitle(getString(
                deleteMode ? R.string.lib_delete_confirm : R.string.lib_delete_mode_title));
        if (!showingWallpapers) {
            toolbar.setTitle(deleteMode
                    ? getString(R.string.lib_delete_mode_count, libAdapter.selectedCount())
                    : getString(R.string.app_title));
        }
    }

    /**
     * 删除态点勾：先弹二次确认（文案写明库数与总壁纸数，确认按钮统一红色垃圾桶图标），
     * 确认后逐库删除。没勾选时只提示不动作。
     */
    private void confirmDeleteSelectedLibs() {
        List<LibraryStore.Library> selected = libAdapter.selectedLibs();
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.lib_delete_none, Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.lib_delete_batch_title, selected.size()))
                .setMessage(R.string.lib_delete_batch_msg)
                .setPositiveButton(" ", (d, which) -> deleteLibs(selected))
                .setNegativeButton(R.string.cancel, null)
                .show();
        // 确认按钮统一红色垃圾桶图标（沿用原删库确认的样式）
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        positive.setText("");
        positive.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_delete, 0, 0, 0);
        positive.setCompoundDrawableTintList(ColorStateList.valueOf(getColor(R.color.danger)));
    }

    /** 批量删库：连带清库内壁纸文件、切换进度与定时任务（都在 LibraryStore.delete 内部）。 */
    private void deleteLibs(List<LibraryStore.Library> libs) {
        boolean removedCurrent = false;
        for (LibraryStore.Library lib : libs) {
            LibraryStore.delete(this, lib.id);
            if (lib.id.equals(currentLibId())) {
                removedCurrent = true;
            }
        }
        if (removedCurrent) {
            currentLibId = null;
            prefs.edit().remove(LibraryStore.KEY_CURRENT_LIB).apply();
        }
        exitLibDeleteMode();
        refreshLibs();
        refreshTimerStatus();
        // 删除启用中的库会改变「谁在被接管」，同步一次接管状态
        syncTakeoverAsync();
    }


    /**
     * 范围卡片齿轮：本范围的聚合设置弹窗 —— 壁纸库（点条目弹出带缩略图的列表）、切换模式（顺序/随机）、
     * 切换间隔（整行可点，弹时/分滚轮）。改动即保存；槽位写入都在 LibraryStore 里。
     */
    private void showSlotSettingsDialog(final boolean forHome) {
        final View content = LayoutInflater.from(this).inflate(R.layout.dialog_slot_settings, null, false);
        bindSlotLibRow(content, forHome);
        bindThemeControls(content);
        content.findViewById(R.id.row_slot_lib).setOnClickListener(
                v -> showLibPickerDialog(forHome, content));
        RadioGroup rgMode = content.findViewById(R.id.rg_mode);
        final TextView tvInterval = content.findViewById(R.id.tv_interval);
        tvInterval.setText(slotIntervalDisplay(forHome));
        new MaterialAlertDialogBuilder(this)
                .setTitle(forHome ? R.string.slot_home_title : R.string.slot_lock_title)
                .setView(content)
                .setNegativeButton(R.string.close, null)
                .show();
        // 回填模式（先 check 再挂监听，程序化 check 不触发写回）
        rgMode.check(LibraryStore.MODE_RANDOM.equals(LibraryStore.scopeMode(this, forHome))
                ? R.id.rb_random : R.id.rb_order);
        // 模式只影响卡片副标题（间隔不变、定时不动），不必重排定时也不必重绘库列表
        rgMode.setOnCheckedChangeListener((group, checkedId) -> {
            LibraryStore.setScopeMode(MainActivity.this, forHome,
                    checkedId == R.id.rb_random
                            ? LibraryStore.MODE_RANDOM : LibraryStore.MODE_ORDER);
            refreshSlotCards();
        });
        // 切换间隔：整行可点，弹时/分滚轮
        content.findViewById(R.id.row_interval).setOnClickListener(
                v -> showIntervalPicker(forHome, tvInterval));
    }

    /** 弹窗里「当前库」那一行的回填（选中库后要重新绑定，缩略图跟着换）。 */
    private void bindSlotLibRow(View content, boolean forHome) {
        TextView row = content.findViewById(R.id.tv_slot_lib);
        ImageView thumb = content.findViewById(R.id.img_slot_lib_thumb);
        LibraryStore.Library lib = LibraryStore.slotLib(this, forHome);
        if (lib == null) {
            row.setText(R.string.slot_lib_none);
            bindThumb(thumb, null, R.drawable.ic_tab_wallpaper, 10);
            return;
        }
        row.setText(lib.name);
        bindThumb(thumb, slotThumbId(lib.id, forHome), R.drawable.ic_tab_wallpaper, 10);
    }

    /**
     * 聚合设置里的配色：色相那条画成整圈色带、浓淡那条画成「当前色相从纸白到墨」，
     * 底下那块小卡片实时跟着变。松手才写偏好并重绑真卡片 —— 拖着每像素都写盘、
     * 每像素都重排两千页滑块没有意义。
     */
    private void bindThemeControls(final View content) {
        final SeekBar sbHue = content.findViewById(R.id.sb_slot_hue);
        final SeekBar sbTone = content.findViewById(R.id.sb_slot_tone);
        sbHue.setMax(SlotTheme.HUE_MAX);
        sbTone.setMax(SlotTheme.TONE_MAX);
        sbHue.setProgress(SlotTheme.hue(this));
        sbTone.setProgress(SlotTheme.tone(this));
        // 色带画在背景上，进度条本身涂透明，拇指就正好压在色带上
        sbHue.setProgressDrawable(new ColorDrawable(Color.TRANSPARENT));
        sbTone.setProgressDrawable(new ColorDrawable(Color.TRANSPARENT));
        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                paintThemePreview(content, sbHue.getProgress(), sbTone.getProgress());
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                SlotTheme.set(MainActivity.this, sbHue.getProgress(), sbTone.getProgress());
                refreshSlotCards();
            }
        };
        sbHue.setOnSeekBarChangeListener(listener);
        sbTone.setOnSeekBarChangeListener(listener);
        paintThemePreview(content, sbHue.getProgress(), sbTone.getProgress());
    }

    /** 按给定的色相/浓淡刷两条色带与预览卡（预览与真卡片走同一套 SlotTheme 换算）。 */
    private void paintThemePreview(View content, int hue, int tone) {
        MaterialCardView preview = content.findViewById(R.id.card_slot_preview);
        preview.setCardBackgroundColor(SlotTheme.color(hue, tone));
        preview.setStrokeColor(SlotTheme.stroke(hue, tone));
        ((TextView) content.findViewById(R.id.tv_slot_preview_scope))
                .setTextColor(SlotTheme.scopeText(this, tone));
        ((TextView) content.findViewById(R.id.tv_slot_preview_name))
                .setTextColor(SlotTheme.nameText(this, tone));
        ((TextView) content.findViewById(R.id.tv_slot_preview_desc))
                .setTextColor(SlotTheme.dimText(this, tone));
        content.findViewById(R.id.img_slot_preview_thumb)
                .setBackgroundColor(SlotTheme.thumbBg(this, hue, tone));
        ((ImageView) content.findViewById(R.id.img_slot_preview_pause))
                .setImageTintList(getColorStateList(R.color.text_secondary));
        // 色带的每一格都按当前浓淡算，浓淡条则按当前色相算 —— 两条互相反映对方
        int[] hueStops = new int[13];
        for (int i = 0; i < hueStops.length; i++) {
            hueStops[i] = SlotTheme.color(i * (360 / (hueStops.length - 1)), tone);
        }
        int[] toneStops = new int[9];
        for (int i = 0; i < toneStops.length; i++) {
            toneStops[i] = SlotTheme.color(hue, i * (SlotTheme.TONE_MAX / (toneStops.length - 1)));
        }
        content.findViewById(R.id.sb_slot_hue).setBackground(gradientBar(hueStops));
        content.findViewById(R.id.sb_slot_tone).setBackground(gradientBar(toneStops));
    }

    /** 一条圆角色带（滑杆的背景）。 */
    private GradientDrawable gradientBar(int[] colors) {
        GradientDrawable bar = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors);
        bar.setCornerRadius(13 * getResources().getDisplayMetrics().density);
        return bar;
    }

    /** 库选择列表：第 0 项「不切换」（清空本范围），其余带各自缩略图；当前占位库打勾。 */
    private void showLibPickerDialog(final boolean forHome, final View settingsContent) {
        final List<LibraryStore.Library> libs = LibraryStore.load(this);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.slot_pick_lib_title)
                .setAdapter(new SlotLibPickerAdapter(libs, forHome), (dialog, which) -> {
                    String newId = which == 0 ? null : libs.get(which - 1).id;
                    String liveId = LibraryStore.slotLibId(this, forHome);
                    if (newId == null ? liveId == null : newId.equals(liveId)) {
                        return;
                    }
                    // 列表点选只是「选中」，落库前再确认一次（同一范围只认一个库）
                    confirmSlotLib(forHome, newId, settingsContent);
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    /** 范围内库变更：槽位写入 + 受影响的两处界面刷新（角标与卡片），接管状态同步一次。 */
    private void applySlotLib(boolean forHome, String libId) {
        LibraryStore.setSlotLib(this, forHome, libId);
        // 桌面这一面由引擎在画：换了占位库要主动标脏，否则得等下次亮屏（onVisibilityChanged 重放）
        // 才换成新库那张 —— 刚选完看不出动，像没生效。锁屏那面走 setBitmap，由下面的 syncTakeover 负责。
        // 引擎没被系统选中时 ENGINES 是空的，这一声就是空响，不必额外判断。
        if (forHome) {
            WallSwitchService.notifyWallpaperChanged();
        }
        libAdapter.refreshBadges();
        refreshTimerStatus();
        refreshSlotCards();
        syncTakeoverAsync();
    }

    /** 选库的二次确认：中性文案说明要改成哪个库（清空那档另说一句会停止切换）。 */
    private void confirmSlotLib(final boolean forHome, final String newLibId,
                                final View settingsContent) {
        String slotName = getString(forHome ? R.string.slot_home_title : R.string.slot_lock_title);
        String message;
        if (newLibId == null) {
            message = getString(R.string.slot_confirm_clear, slotName);
        } else {
            LibraryStore.Library target = LibraryStore.get(this, newLibId);
            message = getString(R.string.slot_confirm_msg, slotName,
                    target == null ? "" : target.name);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.slot_confirm_title)
                .setMessage(message)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    applySlotLib(forHome, newLibId);
                    // 拖拽落位那条路径没有开着的弹窗要重绑，只有设置弹窗里选库时才回填那一行
                    if (settingsContent != null) {
                        bindSlotLibRow(settingsContent, forHome);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 该范围的设置摘要行：模式 · 每 X（卡片与弹窗共用）。 */
    private String slotIntervalDisplay(boolean forHome) {
        boolean random = LibraryStore.MODE_RANDOM.equals(LibraryStore.scopeMode(this, forHome));
        return getString(random ? R.string.mode_random : R.string.mode_order)
                + " · " + getString(R.string.slot_every_prefix)
                + formatInterval(LibraryStore.scopeIntervalSeconds(this, forHome));
    }

    /**
     * 间隔滚轮选择器（时/分上下滑）：分钟按 15 步进（00/15/30/45），下限 15 分钟、上限 23:45。
     */
    private void showIntervalPicker(final boolean forHome, final TextView tvInterval) {
        int minutesTotal = Math.max(15, LibraryStore.scopeIntervalSeconds(this, forHome) / 60);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(pad, pad / 2, pad, 0);
        final NumberPicker hourPicker = new NumberPicker(this);
        hourPicker.setMinValue(0);
        hourPicker.setMaxValue(23);
        hourPicker.setWrapSelectorWheel(true);
        hourPicker.setFormatter(value -> String.format(Locale.getDefault(), "%02d", value));
        hourPicker.setValue(minutesTotal / 60);
        final NumberPicker minutePicker = new NumberPicker(this);
        minutePicker.setMinValue(0);
        minutePicker.setMaxValue(3);
        minutePicker.setWrapSelectorWheel(true);
        minutePicker.setDisplayedValues(new String[]{"00", "15", "30", "45"});
        minutePicker.setValue((minutesTotal % 60) / 15);
        row.addView(wheelColumn(hourPicker, getString(R.string.interval_hour)));
        row.addView(wheelColumn(minutePicker, getString(R.string.interval_minute)));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.lib_interval)
                .setView(row)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    int seconds = (hourPicker.getValue() * 60 + minutePicker.getValue() * 15) * 60;
                    LibraryStore.setScopeInterval(MainActivity.this, forHome, seconds);
                    tvInterval.setText(slotIntervalDisplay(forHome));
                    refreshTimerStatus();
                    refreshSlotCards();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 滚轮选择器的一列：上下滚轮 + 底部「时/分」标签。 */
    private LinearLayout wheelColumn(NumberPicker picker, String label) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.addView(picker);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(getColor(R.color.text_secondary));
        tv.setTextSize(12);
        tv.setGravity(Gravity.CENTER);
        column.addView(tv);
        return column;
    }

    /** 间隔展示：60 的整数倍显示分钟，其余显示「X分Y秒」或「X秒」（兼容历史秒级值）。 */
    private String formatInterval(int seconds) {
        if (seconds >= 60 && seconds % 60 == 0) {
            return (seconds / 60) + "分钟";
        }
        if (seconds >= 60) {
            return (seconds / 60) + "分" + (seconds % 60) + "秒";
        }
        return seconds + "秒";
    }

    /**
     * 手动切换：按该库自己的进度推进一张并上屏（与小组件、定时共用 Switcher.next 收口）。
     * 切换含大图解码、系统调用与失败时的延迟重试（Switcher 内部），放后台线程避免卡 UI。
     */
    private void switchAndToast(final LibraryStore.Library lib, final boolean forHome) {
        final Context app = getApplicationContext();
        new Thread(() -> {
            final boolean ok = Switcher.next(app, lib.id, forHome);
            if (ok) {
                // 手动切完 = 这一轮已经切过一次，定时从此刻重新起算（免得刚切完紧接着又被自动切）
                TimerScheduler.restartScope(app, forHome);
            }
            runOnUiThread(() -> {
                if (ok) {
                    Toast.makeText(this, R.string.switch_done, Toast.LENGTH_SHORT).show();
                    // 切完锁屏后「桌面/锁屏：WallPaper / 系统」这行会变，立刻刷新，别等下次进应用
                    refreshTakeoverStatus();
                    // 下次预计时间也被重排了，状态行同步刷新
                    refreshTimerStatus();
                    // 范围卡片的缩略图 = 该范围在屏那张，切完就该换
                    refreshSlotCards();
                } else {
                    // 带上具体失败原因（如桌面被动态壁纸占用），方便对症处理
                    Toast.makeText(this, Switcher.errorText(this, Switcher.lastError()),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "manual-switch").start();
    }


    /**
     * 定时状态行：桌面/锁屏各一行「上次执行结果 + 下次预计时间」；若已过预计时间仍未见系统执行，
     * 说明被 Doze/ROM 冻结拦住了（等待调度，点亮屏幕/刷新小组件/打开本应用会自动补切）。
     * v3.60 起按范围槽位展示：没设库的范围不显示，两个都空显示引导文案。
     */
    private void refreshTimerStatus() {
        TextView tv = findViewById(R.id.tv_timer_status);
        if (tv == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (boolean forHome : new boolean[]{true, false}) {
            if (LibraryStore.slotLibId(this, forHome) == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(getString(forHome ? R.string.scope_home : R.string.scope_lock))
                    .append("：").append(scopeTimerLine(forHome));
        }
        if (sb.length() == 0) {
            tv.setText(R.string.timer_status_none);
            return;
        }
        tv.setText(sb.toString());
    }

    /** 单个范围的定时状态文案：上次 时间（结果）｜下次预计 时间；过点未执行给补切提示。 */
    private String scopeTimerLine(boolean forHome) {
        if (LibraryStore.slotPaused(this, forHome)) {
            // 暂停的那一面没有「下次」：任务已撤，倒计时留着只会骗人
            return getString(R.string.slot_paused_label);
        }
        long next = TimerScheduler.scopeTrigger(this, forHome);
        long now = System.currentTimeMillis();
        if (next >= 0 && next <= now) {
            return getString(R.string.timer_status_overdue,
                    formatInterval((int) Math.max(60, (now - next) / 1000)));
        }
        String result = TimerScheduler.lastResult(this, forHome);
        String resultText;
        if (TimerScheduler.RESULT_OK.equals(result)) {
            resultText = getString(R.string.status_ok);
        } else if (result == null) {
            resultText = getString(R.string.status_never);
        } else {
            // 失败原因统一走可读文案映射（解码失败/系统未应用/被动态壁纸占用等）
            resultText = Switcher.errorText(this, result);
        }
        long last = TimerScheduler.lastRun(this, forHome);
        String lastText = last > 0 ? formatClock(last) : getString(R.string.status_never);
        String nextText = next >= 0 ? formatClock(next) : getString(R.string.status_never);
        return getString(R.string.timer_status_on, lastText, resultText, nextText);
    }

    /** 时间戳 → HH:mm。 */
    private String formatClock(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    /** 电池优化白名单行的状态：直接显示当前是否已允许（决定后台定时能否被系统唤醒）。 */
    private void refreshBatteryRow() {
        TextView tv = findViewById(R.id.tv_battery);
        if (tv != null) {
            tv.setText(isIgnoringBatteryOptimizations()
                    ? R.string.battery_state_on : R.string.battery_state_off);
        }
    }

    /** 是否已允许忽略电池优化。 */
    private boolean isIgnoringBatteryOptimizations() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    /** 检查电池优化白名单：未忽略且未提示过时弹一次引导。 */
    private void maybePromptBattery() {
        if (isIgnoringBatteryOptimizations()) {
            return;
        }
        if (prefs.getBoolean("battery_prompted", false)) {
            return;
        }
        prefs.edit().putBoolean("battery_prompted", true).apply();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.battery_prompt_title)
                .setMessage(R.string.battery_prompt_msg)
                .setPositiveButton(R.string.confirm, (dialog, which) -> requestIgnoreBattery())
                // 荣耀/华为还需在应用详情里开启「自启动/后台运行」
                .setNeutralButton(R.string.app_details, (dialog, which) -> openAppDetails())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 打开本应用的系统详情页（荣耀/华为在此开启自启动、后台运行白名单）。 */
    private void openAppDetails() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    /** 跳转系统「忽略电池优化」授权页（个别机型不支持该 action 时静默）。 */
    private void requestIgnoreBattery() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }


    /** 打开系统相册多选。 */
    private void launchPicker() {
        PickVisualMediaRequest.Builder builder = new PickVisualMediaRequest.Builder();
        builder.setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE);
        pickLauncher.launch(builder.build());
    }

    /**
     * 多选回调：后台线程逐张复制进收件箱（主线程同步复制多张大图易卡顿、易瞬断失败），
     * 完成后按真实成功/失败数量提示，并打开第一张待编辑项。
     */
    private void onPicked(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }
        View btnAdd = findViewById(R.id.btn_add);
        btnAdd.setEnabled(false);
        new Thread(() -> {
            int success = 0;
            int failed = 0;
            for (Uri uri : uris) {
                try {
                    WallpaperStore.importToInbox(this, uri);
                    success++;
                } catch (Exception e) {
                    failed++;
                }
            }
            List<String> pending = WallpaperStore.pendingInbox(this);
            // lambda 捕获要求实际 final：先把计数定稿
            final int okCount = success;
            final int failCount = failed;
            runOnUiThread(() -> {
                btnAdd.setEnabled(true);
                if (failCount > 0) {
                    if (okCount > 0) {
                        Toast.makeText(this, getString(R.string.import_partial, okCount, failCount),
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, R.string.import_all_failed, Toast.LENGTH_LONG).show();
                    }
                } else {
                    Toast.makeText(this, getString(R.string.import_done, okCount),
                            Toast.LENGTH_SHORT).show();
                }
                if (!pending.isEmpty()) {
                    openEdit(pending.get(0), currentLibId());
                }
            });
        }, "inbox-import").start();
    }

    /** 打开编辑页处理某个收件箱文件（确认后入库到指定库）。 */
    private void openEdit(String inboxId, String libId) {
        Intent intent = new Intent(this, EditActivity.class);
        intent.putExtra(EditActivity.EXTRA_INBOX_ID, inboxId);
        intent.putExtra(EditActivity.EXTRA_LIB_ID, libId);
        startActivity(intent);
    }

    /** 打开编辑页重新裁剪一张已入库壁纸（点壁纸格直接进入；确认后覆盖成品图，源是原图）。 */
    private void openEditItem(WallpaperStore.Item item) {
        Intent intent = new Intent(this, EditActivity.class);
        intent.putExtra(EditActivity.EXTRA_ITEM_ID, item.id);
        startActivity(intent);
    }

    /**
     * 刷新接管相关界面（对齐 Muzei：没有开关，状态即系统真值）。
     * 顶部横幅在"未启用"时给出启用入口；已启用但没有启用库时提示会显示纯色。
     * 状态行读系统真值，所以用户在系统设置里换了壁纸后回到应用就会自动反映。
     */
    private void refreshTakeoverUi() {
        boolean active = TakeoverManager.isHomeTakenOver(this);
        TextView state = findViewById(R.id.tv_takeover_state);
        if (state != null) {
            state.setText(active ? R.string.takeover_state_on : R.string.takeover_state_off);
            state.setTextColor(getColor(active ? R.color.brand : R.color.text_secondary));
        }
        View info = findViewById(R.id.iv_takeover_info);
        if (info != null) {
            info.setOnClickListener(v -> showTakeoverInfoDialog());
        }
        View banner = findViewById(R.id.banner_takeover);
        TextView bannerText = findViewById(R.id.tv_banner_text);
        View activate = findViewById(R.id.btn_activate);
        if (banner != null && bannerText != null) {
            LibraryStore.Library homeLib = LibraryStore.slotLib(this, true);
            if (!active) {
                banner.setVisibility(View.VISIBLE);
                bannerText.setText(R.string.banner_not_activated);
                if (activate != null) {
                    activate.setVisibility(View.VISIBLE);
                }
            } else if (homeLib == null) {
                banner.setVisibility(View.VISIBLE);
                bannerText.setText(R.string.banner_no_library);
                // 这种情况要去的是库列表（就在下面），不需要"启用"按钮
                if (activate != null) {
                    activate.setVisibility(View.GONE);
                }
            } else {
                banner.setVisibility(View.GONE);
            }
        }
        refreshTakeoverStatus();
    }

    /** 关闭接管的唯一途径说明（点感叹号图标时弹）。 */
    private void showTakeoverInfoDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.takeover_info_title)
                .setMessage(R.string.takeover_info_msg)
                .setPositiveButton(R.string.takeover_info_open_settings, (d, which) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_SET_WALLPAPER));
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 启用：直接拉起系统动态壁纸选择器（本应用没有"关闭"入口，所以不需要任何存档步骤；
     * 要备份系统壁纸请用抽屉里的「保存当前壁纸」）。
     */
    private void enableTakeover() {
        WallSwitchService.openActivator(this);
    }

    /** 跳系统设置页开「所有文件访问」；部分 ROM 不支持直达本 App 时退回总列表页。 */
    private void requestAllFilesAccess() {
        Toast.makeText(this, "需要「所有文件访问」权限来读取当前系统壁纸，请允许后返回", Toast.LENGTH_LONG).show();
        try {
            allFilesLauncher.launch(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            allFilesLauncher.launch(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    /** 从「所有文件访问」设置页返回：已开启就继续保存当前壁纸，没开就取消。 */
    private void onAllFilesAccessResult() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            doSaveCurrentWallpaper();
        } else {
            Toast.makeText(this, "未开启「所有文件访问」，无法读取当前系统壁纸", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 抽屉「保存当前壁纸」入口：接管中不需要权限（图就在库里），
     * 未接管要读系统壁纸，需要「所有文件访问」（Android 11+）/ 读存储权限（更低版本）。
     */
    private void startSaveCurrentWallpaper() {
        if (TakeoverManager.isHomeTakenOver(this)) {
            doSaveCurrentWallpaper();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 13+ 的照片权限折算不了 WallpaperManager 要的 READ_EXTERNAL_STORAGE
            // （真机实测 MagicOS），只能走「所有文件访问」隐式获得（v3.28 实验证实必需）
            if (!Environment.isExternalStorageManager()) {
                requestAllFilesAccess();
                return;
            }
            doSaveCurrentWallpaper();
            return;
        }
        String perm = Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            savePermLauncher.launch(perm);
            return;
        }
        doSaveCurrentWallpaper();
    }

    /** 后台执行保存，全程盖不可关闭的进度弹窗，结果用 Toast 告知。 */
    private void doSaveCurrentWallpaper() {
        showProgress(R.string.save_wallpaper_title, R.string.save_wallpaper_working);
        new Thread(() -> {
            final String fail = TakeoverManager.saveCurrentWallpaper(this);
            runOnUiThread(() -> {
                dismissTakeoverProgress();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                CharSequence msg = fail.isEmpty()
                        ? getString(R.string.save_wallpaper_done)
                        : getString(R.string.save_wallpaper_failed, fail);
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            });
        }, "save-wallpaper").start();
    }

    /** 进度弹窗：转圈提示、不可关闭（假进度；存档/导出全程只有几秒）。 */
    private void showProgress(int titleRes, int msgRes) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        box.setPadding(pad, pad, pad, pad);
        ProgressBar bar = new ProgressBar(this);
        box.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView tv = new TextView(this);
        tv.setText(msgRes);
        tv.setPadding(pad / 2, 0, 0, 0);
        tv.setTextColor(getColor(R.color.text_primary));
        box.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        takeoverProgress = new MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setView(box)
                .setCancelable(false)
                .create();
        takeoverProgress.show();
    }

    /** 收起进度弹窗（容错：Activity 已销毁时 dismiss 会抛，吞掉即可）。 */
    private void dismissTakeoverProgress() {
        if (takeoverProgress != null) {
            try {
                takeoverProgress.dismiss();
            } catch (Exception ignored) {
            }
            takeoverProgress = null;
        }
    }

    /**
     * 把接管落地一次（对齐当前启用库：锁屏库 → 设锁屏；桌面有库但引擎没激活 → 提示去启用）。
     * 放后台线程，避免系统调用卡 UI。
     */
    private void syncTakeoverAsync() {
        new Thread(() -> {
            final int result = TakeoverManager.apply(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (result == TakeoverManager.RESULT_NEED_ACTIVATION) {
                    Toast.makeText(this, R.string.takeover_need_activate, Toast.LENGTH_LONG).show();
                }
                refreshTakeoverUi();
            });
        }, "takeover-sync").start();
    }

    /**
     * 接管情况（系统真值）：桌面看引擎是否生效；锁屏三态 —— 设过独立锁屏壁纸 → WallPaper，
     * 否则引擎激活（顺带盖住锁屏）→ WallPaper，否则 → 系统。取值只写 WallPaper / 系统。
     */
    private void refreshTakeoverStatus() {
        setScopeStatus(R.id.tv_takeover_home, TakeoverManager.isHomeTakenOver(this));
        boolean lockByApp = TakeoverManager.isLockTakenOver(this)
                || TakeoverManager.isLockTakenByEngine(this);
        setScopeStatus(R.id.tv_takeover_lock, lockByApp);
    }

    /** 一行范围状态：值只写 WallPaper / 系统（WallPaper 用主色，系统用次级灰）。 */
    private void setScopeStatus(int viewId, boolean byApp) {
        TextView tv = findViewById(viewId);
        if (tv == null) {
            return;
        }
        tv.setText(byApp ? R.string.takeover_by_app : R.string.takeover_by_system);
        tv.setTextColor(getColor(byApp ? R.color.brand : R.color.text_secondary));
    }

    /** 选一张首页截图作为全局「桌面图标预览底图」（抠图后存应用目录，之后不用再选）。 */
    private void pickLauncherOverlay() {
        PickVisualMediaRequest.Builder builder = new PickVisualMediaRequest.Builder();
        builder.setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE);
        overlayLauncher.launch(builder.build());
    }

    /** 选好后的处理：后台抠图（把截图自带的壁纸变透明）→ 存成全局底图 → 回显状态。 */
    private void onOverlayPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        Toast.makeText(this, R.string.launcher_overlay_processing, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final Bitmap keyed = LauncherPreviewOverlay.keyedFromUri(this, uri);
            final boolean saved = keyed != null && LauncherPreviewOverlay.save(this, keyed);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Toast.makeText(this,
                        saved ? R.string.launcher_overlay_saved : R.string.launcher_overlay_failed,
                        Toast.LENGTH_SHORT).show();
                refreshLauncherOverlayRow();
            });
        }, "overlay-key").start();
    }

    /** 清除全局预览底图（抽屉里长按）。 */
    private void confirmClearLauncherOverlay() {
        if (!LauncherPreviewOverlay.overlayFile(this).exists()) {
            Toast.makeText(this, R.string.launcher_overlay_unset, Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.launcher_overlay_title)
                .setMessage(R.string.launcher_overlay_clear_msg)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    LauncherPreviewOverlay.clear(MainActivity.this);
                    refreshLauncherOverlayRow();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 已设置底图后点按抽屉行：浮出查看/更换/清除菜单（长按仍是清除快捷方式）。 */
    private void showLauncherOverlayMenu() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.launcher_overlay_title)
                .setItems(new CharSequence[]{
                        getString(R.string.launcher_overlay_action_view),
                        getString(R.string.launcher_overlay_action_change),
                        getString(R.string.launcher_overlay_action_clear)},
                        (dialog, which) -> {
                            if (which == 0) {
                                showLauncherOverlayPreview();
                            } else if (which == 1) {
                                pickLauncherOverlay();
                            } else {
                                confirmClearLauncherOverlay();
                            }
                        })
                .show();
    }

    /** 预览对话框：在棋盘格衬底上展示抠好的透明底图，透明区域一眼可见。 */
    private void showLauncherOverlayPreview() {
        ImageView image = new ImageView(this);
        image.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (280 * getResources().getDisplayMetrics().density)));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding((int) (16 * getResources().getDisplayMetrics().density),
                (int) (16 * getResources().getDisplayMetrics().density),
                (int) (16 * getResources().getDisplayMetrics().density),
                (int) (16 * getResources().getDisplayMetrics().density));
        image.setBackground(checkerboard());
        ScrollView scroll = new ScrollView(this);
        scroll.addView(image);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.launcher_overlay_title)
                .setView(scroll)
                .setNegativeButton(R.string.close, null)
                .show();
        new Thread(() -> {
            final Bitmap saved = LauncherPreviewOverlay.loadSaved(this);
            runOnUiThread(() -> {
                if (saved == null) {
                    dialog.dismiss();
                    Toast.makeText(this, R.string.launcher_overlay_load_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                image.setImageBitmap(saved);
            });
        }, "overlay-preview").start();
    }

    /** 灰白棋盘格衬底（2×2 平铺单元），用来显出底图的透明区域。 */
    private Drawable checkerboard() {
        float cell = 12 * getResources().getDisplayMetrics().density;
        int size = (int) (cell * 2);
        Bitmap tile = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(tile);
        canvas.drawColor(0xFFF2F2F2);
        Paint gray = new Paint();
        gray.setColor(0xFFD9D9D9);
        canvas.drawRect(0, 0, cell, cell, gray);
        canvas.drawRect(cell, cell, size, size, gray);
        BitmapDrawable drawable = new BitmapDrawable(getResources(), tile);
        drawable.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        return drawable;
    }

    /** 抽屉里那行的状态文案：已设置 / 未设置。 */
    private void refreshLauncherOverlayRow() {
        TextView state = findViewById(R.id.tv_launcher_overlay);
        if (state == null) {
            return;
        }
        state.setText(LauncherPreviewOverlay.overlayFile(this).exists()
                ? R.string.launcher_overlay_set : R.string.launcher_overlay_unset);
    }


    /** 选一个导出目录（SAF）：选完记住并申请持久化授权。 */
    private void onExportDirPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        WallpaperExporter.setTreeUri(this, uri);
        refreshExportRows();
        Toast.makeText(this, R.string.export_dir_set, Toast.LENGTH_SHORT).show();
    }

    /** 取消导出目录（不清除已经导出的文件）。 */
    private void confirmClearExportDir() {
        if (!WallpaperExporter.isConfigured(this)) {
            Toast.makeText(this, R.string.export_dir_unset, Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.export_dir_title)
                .setMessage(R.string.export_dir_clear_msg)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    WallpaperExporter.clear(MainActivity.this);
                    refreshExportRows();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 把库内全部壁纸导出到该目录（纯 IO，放后台线程）。 */
    private void exportAllWallpapers() {
        if (!WallpaperExporter.isConfigured(this)) {
            Toast.makeText(this, R.string.export_need_dir, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.export_running, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final int count = WallpaperExporter.exportAll(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Toast.makeText(MainActivity.this, getString(R.string.export_done, count),
                        Toast.LENGTH_LONG).show();
            });
        }, "wallpaper-export-all").start();
    }

    /** 备份整库：一个 zip 装下图、缩略图、两个 json 和 prefs，写进导出目录。 */
    private void startBackup() {
        if (!WallpaperExporter.isConfigured(this)) {
            Toast.makeText(this, R.string.export_need_dir, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.backup_running, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final BackupStore.BackupResult r = BackupStore.backup(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (r.error != null) {
                    Toast.makeText(this, getString(R.string.backup_failed, r.error),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                prefs.edit().putString("backup_last_name", r.name)
                        .putLong("backup_last_at", System.currentTimeMillis()).apply();
                refreshBackupRow();
                Toast.makeText(this, getString(R.string.backup_done, r.name, r.entries,
                        r.images, r.originals, r.thumbs), Toast.LENGTH_LONG).show();
            });
        }, "lib-backup").start();
    }

    /** 选中备份包后先回显现场，确认了才动本机数据。 */
    private void onRestoreZipPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        showProgress(R.string.restore_title, R.string.restore_reading);
        new Thread(() -> {
            final BackupStore.Manifest m = BackupStore.inspect(getApplicationContext(), uri);
            runOnUiThread(() -> {
                dismissTakeoverProgress();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (m == null) {
                    Toast.makeText(this, R.string.restore_bad_zip, Toast.LENGTH_LONG).show();
                    return;
                }
                confirmRestore(uri, m);
            });
        }, "backup-inspect").start();
    }

    private void confirmRestore(Uri uri, BackupStore.Manifest m) {
        int local = WallpaperStore.load(this).size();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.restore_title)
                .setMessage(getString(R.string.restore_confirm,
                        m.backupTime == null || m.backupTime.isEmpty() ? "未知" : m.backupTime,
                        m.appVersion == null || m.appVersion.isEmpty() ? "未知" : m.appVersion,
                        m.libraryItems, m.images, m.originals, m.thumbs, m.bytes / (1024 * 1024), local))
                .setPositiveButton(R.string.restore_go, (dialog, which) -> runRestore(uri))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 执行还原。还原后不必自己重排定时：onResume 本来就会 scheduleAll + 补切各走一遍
     * （自愈那条路），recreate 之后正好落到它上面。
     */
    private void runRestore(Uri uri) {
        Toast.makeText(this, R.string.restore_running, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final BackupStore.RestoreResult r = BackupStore.restore(getApplicationContext(), uri);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (r.error != null) {
                    Toast.makeText(this, getString(R.string.restore_failed, r.error),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this, getString(R.string.restore_done, r.items, r.prefsKeys,
                        r.images, r.droppedItems), Toast.LENGTH_LONG).show();
                recreate();
            });
        }, "lib-restore").start();
    }

    /** 上一次备份的时间与文件名（只记在本机，重装后是空的，这正常）。 */
    private void refreshBackupRow() {
        TextView state = findViewById(R.id.tv_backup);
        if (state == null) {
            return;
        }
        long at = prefs.getLong("backup_last_at", 0L);
        String name = prefs.getString("backup_last_name", "");
        state.setText(at == 0L || name == null || name.isEmpty()
                ? getString(R.string.backup_desc)
                : getString(R.string.backup_last, TimerScheduler.clockText(this, at), name));
    }

    /** 导出目录那行的状态回显。 */
    private void refreshExportRows() {
        TextView state = findViewById(R.id.tv_export_dir);
        if (state == null) {
            return;
        }
        state.setText(WallpaperExporter.isConfigured(this)
                ? getString(R.string.export_dir_set_state, WallpaperExporter.displayName(this))
                : getString(R.string.export_dir_unset));
    }

    /** 切换日志那行的状态回显：最近一条记录的时间（没有则提示还没有记录）。纯读文件，放后台线程。 */
    /** 日志行长按：清空全部记录（老格式记录不会自动改写，留着一堆旧格式照样难看）。 */
    private void confirmClearSwitchLog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.log_clear_title)
                .setMessage(R.string.log_clear_msg)
                .setPositiveButton(R.string.confirm, (dialog, which) -> new Thread(() -> {
                    SwitchLog.clear(getApplicationContext());
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        Toast.makeText(this, R.string.log_clear_done, Toast.LENGTH_SHORT).show();
                        refreshSwitchLogRow();
                    });
                }, "log-clear").start())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void refreshSwitchLogRow() {
        final TextView state = findViewById(R.id.tv_switch_log);
        if (state == null) {
            return;
        }
        new Thread(() -> {
            final Context app = getApplicationContext();
            final String home = SwitchLog.latestTimeLabel(app, true);
            final String lock = SwitchLog.latestTimeLabel(app, false);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                state.setText(home == null && lock == null
                        ? getString(R.string.log_view_empty_row)
                        : getString(R.string.log_view_latest,
                                home == null ? getString(R.string.log_view_none) : home,
                                lock == null ? getString(R.string.log_view_none) : lock));
            });
        }, "log-row").start();
    }

    /** 读两份日志文件并弹窗展示。纯 IO 放后台线程，读完回主线程弹。 */
    private void showSwitchLog() {
        new Thread(() -> {
            final Context app = getApplicationContext();
            final String home = SwitchLog.readAllText(app, true);
            final String lock = SwitchLog.readAllText(app, false);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                showSwitchLogDialog(home, lock);
            });
        }, "log-read").start();
    }

    /**
     * 日志弹窗：顶部 桌面/锁屏 两个 tab（两份文件各记各的账，混在一起看只会看错），
     * 正文是等宽、可长按选中的可滚动区；当前 tab 非空时给一个「复制」，只复制这一面。
     */
    private void showSwitchLogDialog(String home, String lock) {
        final String[] contents = {home == null ? "" : home, lock == null ? "" : lock};
        final int[] shown = {0};
        final boolean empty = contents[0].trim().isEmpty() && contents[1].trim().isEmpty();

        final TextView body = new TextView(this);
        bindLogBody(body, contents[0]);
        body.setTextSize(12f);
        body.setTextIsSelectable(true);
        body.setTypeface(Typeface.MONOSPACE);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        final TabLayout tabs = new TabLayout(this);
        tabs.addTab(tabs.newTab().setText(R.string.log_tab_home));
        tabs.addTab(tabs.newTab().setText(R.string.log_tab_lock));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                shown[0] = tab.getPosition();
                bindLogBody(body, contents[shown[0]]);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        // 固定高度上限：日志长了在弹窗内部滚动，不会把弹窗撑到超出屏幕
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
        column.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxHeight));
        column.addView(tabs);
        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 用父类型接收：MaterialAlertDialogBuilder 只对 setTitle/setView 做了协变覆盖，
        // setNegativeButton 继承自 AlertDialog.Builder，链式表达式静态类型是 Builder
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.log_view_title)
                .setView(column)
                .setNegativeButton(R.string.cancel, null);
        if (!empty) {
            builder.setNeutralButton(R.string.log_view_copy,
                    (dialog, which) -> copySwitchLog(contents[shown[0]]));
        }
        builder.show();
    }

    /** 弹窗正文换内容（切 tab 时用）：空的那一面给一句说明，不留白屏。 */
    private void bindLogBody(TextView body, String content) {
        body.setText(content == null || content.trim().isEmpty()
                ? getString(R.string.log_view_empty) : content);
    }

    /** 复制日志全文到剪贴板。 */
    private void copySwitchLog(String content) {
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null) {
                return;
            }
            manager.setPrimaryClip(ClipData.newPlainText(getString(R.string.log_view_title), content));
            Toast.makeText(this, R.string.log_view_copied, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }


    /**
     * 长按浮出的铅笔 → 改标题小窗：一个输入框加一行现场信息，不显示图片。
     * 看构图这件事已经归给「点格子进裁剪页」（那里看到的是原图 + 上次的取景框），这里只回答两个问题：
     * 这张叫什么、库里存的到底是多大一张。尺寸只读图片文件头（inJustDecodeBounds），主线程就能拿到，
     * 不再后台解整张图；原图留存情况决定进裁剪页时能不能复原上次取景框。
     */
    private void showRenameDialog(WallpaperStore.Item item) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(item.title == null ? "" : item.title);
        input.setSelection(input.getText().length());
        TextView info = new TextView(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        info.setPadding(pad, 0, pad, 0);
        info.setTextSize(13f);
        info.setTextColor(getColor(R.color.text_secondary));
        info.setText(renameInfoText(item));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(wrapInput(input, R.string.rename_hint));
        content.addView(info);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rename_title)
                .setView(content)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    // 标题会被自动切换的通知带上，用来确认「切到了哪张」；留空 = 未命名
                    String title = input.getText().toString().trim();
                    WallpaperStore.setTitle(this, item.id, title);
                    item.title = title;
                    refreshList();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 改标题小窗那行信息：库里成品图的真实像素尺寸 + 原图有没有留存。 */
    private String renameInfoText(WallpaperStore.Item item) {
        String original = getString(WallpaperStore.hasOriginal(this, item.id)
                ? R.string.rename_info_has_original : R.string.rename_info_no_original);
        File file = WallpaperStore.getFullFile(this, item.id);
        if (!file.isFile()) {
            return getString(R.string.rename_info_missing, original);
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        return getString(R.string.rename_info, bounds.outWidth, bounds.outHeight, original);
    }

    /**
     * 库内长按浮出的「设为首页」：把这张壁纸立刻设为**桌面**壁纸，并把该库的桌面指针移到这里
     * （之后自动切换就从它往下走）。库没启用桌面范围时只提示，不静默改范围 ——
     * 改范围会连带停用同范围的库，那种事该由用户在库设置里决定。
     * 含引擎通知 / 解码与系统调用，放后台线程。
     */
    private void setAsHomeWallpaper(final WallpaperStore.Item item) {
        if (!TakeoverManager.isEnabled(this)) {
            Toast.makeText(this, R.string.status_takeover_off, Toast.LENGTH_LONG).show();
            return;
        }
        LibraryStore.Library lib = LibraryStore.get(this, item.libId);
        if (lib == null || !LibraryStore.ownsScope(this, lib.id, true)) {
            Toast.makeText(this, R.string.set_as_home_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        final Context app = getApplicationContext();
        new Thread(() -> {
            final boolean ok = Switcher.setCurrent(app, lib.id, item.id, true);
            if (ok) {
                // 指定一张上图 = 手动切了一次，桌面这轮的定时从此刻重新起算
                TimerScheduler.restartScope(app, true);
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Toast.makeText(this, ok ? getString(R.string.set_as_home_done)
                        : Switcher.errorText(this, Switcher.lastError()), Toast.LENGTH_SHORT).show();
                if (ok) {
                    // 「桌面：WallPaper / 系统」这行可能因此变化，立刻刷新，别等下次进应用
                    refreshTakeoverStatus();
                }
            });
        }, "set-home").start();
    }

    /** 壁纸显示名（未命名则用占位文案）。 */
    private String itemTitle(WallpaperStore.Item item) {
        return item.title == null || item.title.isEmpty() ? getString(R.string.untitled) : item.title;
    }

    /** 弹窗输入框统一套一层 TextInputLayout（M3 描边 + 浮动提示），返回可直接 setView 的容器。 */
    private TextInputLayout wrapInput(EditText input, int hintRes) {
        TextInputLayout wrapper = new TextInputLayout(this);
        wrapper.setHint(getString(hintRes));
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        wrapper.setPadding(padding, 0, padding, 0);
        input.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        wrapper.addView(input);
        return wrapper;
    }


    /** 点库名进入就地改名：TextView 与 EditText 互换可见性，弹起软键盘。 */
    private void enterRename(LibHolder holder, LibraryStore.Library lib) {
        renamingLibId = lib.id;
        holder.tvName.setVisibility(View.GONE);
        holder.etName.setVisibility(View.VISIBLE);
        holder.etName.setText(lib.name);
        holder.etName.setSelection(0, lib.name == null ? 0 : lib.name.length());
        holder.etName.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(holder.etName, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    /** 提交就地改名并退出编辑态（写回 LibraryStore；留空则保留原名）。 */
    private void commitRename(LibHolder holder, LibraryStore.Library lib) {
        // 以防编辑器 action 与失焦回调各触发一次造成重复提交
        if (renamingLibId == null || !renamingLibId.equals(lib.id)) {
            return;
        }
        renamingLibId = null;
        LibraryStore.setName(this, lib.id, holder.etName.getText().toString());
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(holder.etName.getWindowToken(), 0);
        }
        // 可能正处于焦点变化回调中：推迟到下一帧再整体重绑，避免回调过程中回收正在编辑的视图
        recycler.post(this::refreshLibs);
    }

    /** 取消就地改名（不写回），收起输入法并恢复原库名显示。 */
    private void cancelRename() {
        renamingLibId = null;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(recycler.getWindowToken(), 0);
        }
        // 重绑一次，把 EditText 收起、库名 TextView 恢复原值
        refreshLibs();
    }

    /**
     * 库列表适配器（首页）：缩略图（该库当前壁纸）+ 库名（点按就地改名）+ N 张壁纸
     * + 启用开关 + 齿轮；长按行 = 拖拽排序（ItemTouchHelper 驱动，落位写回磁盘）；
     * 删除态（顶栏垃圾桶进入）下开关/齿轮换成行尾复选框，点行即勾选。
     */
    /**
     * 库选择列表的适配器（范围设置弹窗点「壁纸库」后弹出）：
     * 行 = 该范围的代表缩略图 + 库名 + 张数，当前占位库右侧打勾；第 0 项固定是「不切换」。
     * 走 ListView 复用，只解码可见行，库多了也不卡。
     */
    private class SlotLibPickerAdapter extends BaseAdapter {

        private final List<LibraryStore.Library> libs;
        private final boolean forHome;
        // 打开列表时的占位库：整个列表的勾都按它画，不必每行重读 prefs
        private final String liveId;

        SlotLibPickerAdapter(List<LibraryStore.Library> libs, boolean forHome) {
            this.libs = libs;
            this.forHome = forHome;
            this.liveId = LibraryStore.slotLibId(MainActivity.this, forHome);
        }

        @Override
        public int getCount() {
            return libs.size() + 1;
        }

        @Override
        public Object getItem(int position) {
            return position == 0 ? null : libs.get(position - 1);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(MainActivity.this)
                            .inflate(R.layout.item_slot_lib, parent, false);
            TextView name = row.findViewById(R.id.tv_pick_name);
            TextView sub = row.findViewById(R.id.tv_pick_sub);
            ImageView thumb = row.findViewById(R.id.img_pick_thumb);
            View check = row.findViewById(R.id.iv_pick_check);
            if (position == 0) {
                name.setText(R.string.slot_lib_none);
                sub.setText(R.string.slot_none_sub);
                bindThumb(thumb, null, R.drawable.ic_tab_wallpaper, 10);
                check.setVisibility(liveId == null ? View.VISIBLE : View.INVISIBLE);
                return row;
            }
            LibraryStore.Library lib = libs.get(position - 1);
            name.setText(lib.name);
            sub.setText(getString(R.string.lib_count,
                    WallpaperStore.loadByLib(MainActivity.this, lib.id).size()));
            bindThumb(thumb, slotThumbId(lib.id, forHome), R.drawable.ic_tab_wallpaper, 10);
            check.setVisibility(lib.id.equals(liveId) ? View.VISIBLE : View.INVISIBLE);
            return row;
        }
    }

    private class LibAdapter extends RecyclerView.Adapter<LibHolder> {

        // 每行的预计算数据：setItems 时一次性算好张数与缩略图来源，onBindViewHolder 不再做文件 IO
        private static class Row {
            final LibraryStore.Library lib;
            final int count;
            // 缩略图来源 id：该库当前壁纸（桌面指针优先，其次锁屏，再次库内第一张）；空库为 null
            final String thumbId;

            Row(LibraryStore.Library lib, int count, String thumbId) {
                this.lib = lib;
                this.count = count;
                this.thumbId = thumbId;
            }
        }

        private final List<Row> rows = new ArrayList<>();
        // 删除态（顶栏垃圾桶进入）：行尾显示复选框、点行勾选，勾图标确认批量删除
        private boolean deleteMode;
        private final Set<String> selectedIds = new HashSet<>();

        // 缩略图内存缓存（上限 8MB）：每次刷新都重新解码会掉帧。
        // v3.15 缩略图边长跟着屏幕（单张 0.5~1MB），上限跟着调大才够铺满可见行
        private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };

        /** 取缩略图：先查缓存，未命中再解码并存入。 */
        private Bitmap thumbFor(String id) {
            Bitmap cached = thumbs.get(id);
            if (cached != null) {
                return cached;
            }
            Bitmap decoded = WallpaperStore.getThumb(MainActivity.this, id);
            if (decoded != null) {
                thumbs.put(id, decoded);
            }
            return decoded;
        }

        void setItems(List<LibraryStore.Library> newItems) {
            rows.clear();
            for (LibraryStore.Library lib : newItems) {
                // 每个库只在刷新时读一次库内列表（张数 + 缩略图来源），滚动时不再反复读 JSON
                List<WallpaperStore.Item> wallpapers =
                        WallpaperStore.loadByLib(MainActivity.this, lib.id);
                String thumbId = Switcher.getCurrent(MainActivity.this, lib.id, true);
                if (thumbId == null) {
                    thumbId = Switcher.getCurrent(MainActivity.this, lib.id, false);
                }
                if (thumbId == null && !wallpapers.isEmpty()) {
                    thumbId = wallpapers.get(0).id;
                }
                rows.add(new Row(lib, wallpapers.size(), thumbId));
            }
            // 数据已换，之前的勾选不复存在（视图会被回收），标记一并清掉；
            // 改名中的 EditText 也会被重绑回 TextView，所以改名编辑态同步清掉
            // （否则返回键会多拦一次「取消改名」，用户要按两次才退出）
            selectedIds.clear();
            renamingLibId = null;
            notifyDataSetChanged();
            updateLibDeleteToolbar();
        }

        void clearThumbs() {
            thumbs.evictAll();
        }

        boolean isDeleteMode() {
            return deleteMode;
        }

        int selectedCount() {
            return selectedIds.size();
        }

        /** 进入/退出删除态（退出时清空勾选）。 */
        void setDeleteMode(boolean on) {
            deleteMode = on;
            selectedIds.clear();
            notifyDataSetChanged();
        }

        List<LibraryStore.Library> selectedLibs() {
            List<LibraryStore.Library> result = new ArrayList<>();
            for (Row row : rows) {
                if (selectedIds.contains(row.lib.id)) {
                    result.add(row.lib);
                }
            }
            return result;
        }

        /** 当前行顺序对应的库 id 列表（拖拽落位后写回磁盘用）。 */
        List<String> currentIds() {
            List<String> ids = new ArrayList<>();
            for (Row row : rows) {
                ids.add(row.lib.id);
            }
            return ids;
        }

        /** 拖拽换位：只动内存与动画，落盘由 ItemTouchHelper 的 clearView 统一做。 */
        boolean move(int from, int to) {
            if (from < 0 || to < 0 || from >= rows.size() || to >= rows.size()) {
                return false;
            }
            rows.add(to, rows.remove(from));
            notifyItemMoved(from, to);
            return true;
        }

        /**
         * 只刷新行上的「桌面/锁屏」角标：槽位变更后逐行重绑即可。
         * 别用 setItems —— 那会为每个库重读一遍壁纸 JSON 并整表重绘，纯浪费（发热来源之一）。
         */
        void refreshBadges() {
            for (int i = 0; i < rows.size(); i++) {
                notifyItemChanged(i);
            }
        }

        @NonNull
        @Override
        public LibHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(MainActivity.this)
                    .inflate(R.layout.item_library, parent, false);
            return new LibHolder(view, pressSlopPx);
        }


        @Override
        public void onBindViewHolder(@NonNull LibHolder holder, int position) {
            final Row row = rows.get(position);
            final LibraryStore.Library lib = row.lib;
            Bitmap thumb = row.thumbId == null ? null : thumbFor(row.thumbId);
            if (thumb != null) {
                // 布局里那个 tint 是给占位图标用的，不清掉的话它会把真图也按 SRC_IN 染成单一剪影
                // （看起来就像「这个库没有图」）；占位与真图两条路径都必须显式设置，回收复用才不会串状态
                holder.imgThumb.setPadding(0, 0, 0, 0);
                holder.imgThumb.setImageTintList(null);
                holder.imgThumb.setImageBitmap(thumb);
            } else {
                int pad = (int) (12 * getResources().getDisplayMetrics().density);
                holder.imgThumb.setPadding(pad, pad, pad, pad);
                holder.imgThumb.setImageTintList(
                        ColorStateList.valueOf(getColor(R.color.text_secondary)));
                holder.imgThumb.setImageResource(R.drawable.ic_tab_wallpaper);
            }
            holder.tvName.setText(lib.name);
            holder.tvName.setVisibility(View.VISIBLE);
            holder.etName.setVisibility(View.GONE);
            holder.tvCount.setText(getString(R.string.lib_count, row.count));
            // v3.60：库不再有启用/范围属性——被哪个范围槽位引用就显示哪个小标签
            holder.badgeHome.setVisibility(
                    LibraryStore.ownsScope(MainActivity.this, lib.id, true) ? View.VISIBLE : View.GONE);
            holder.badgeLock.setVisibility(
                    LibraryStore.ownsScope(MainActivity.this, lib.id, false) ? View.VISIBLE : View.GONE);
            // 删除态：标签收起，行尾露出复选框（点行任意处即可勾选）
            holder.cbDelete.setVisibility(deleteMode ? View.VISIBLE : View.GONE);
            if (deleteMode) {
                holder.badgeHome.setVisibility(View.GONE);
                holder.badgeLock.setVisibility(View.GONE);
            }
            holder.cbDelete.setOnCheckedChangeListener(null);
            holder.cbDelete.setChecked(selectedIds.contains(lib.id));
            if (deleteMode) {
                holder.cbDelete.setOnCheckedChangeListener((v, checked) -> {
                    if (checked) {
                        selectedIds.add(lib.id);
                    } else {
                        selectedIds.remove(lib.id);
                    }
                    updateLibDeleteToolbar();
                });
            }
            // 点库名 → 就地改名（删除态不响应）；点行内其他位置 → 进该库壁纸页 / 勾选
            holder.tvName.setOnClickListener(v -> {
                if (holder.namePress.dragged()) {
                    return;
                }
                if (!deleteMode) {
                    enterRename(holder, lib);
                }
            });
            holder.etName.setOnEditorActionListener((v, actionId, event) -> {
                commitRename(holder, lib);
                return true;
            });
            holder.etName.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    commitRename(holder, lib);
                }
            });
            holder.card.setOnClickListener(v -> {
                // 右滑开抽屉这类滑动手势的起点就在行上，松手也还在行内：滑动过就不算点击
                if (holder.cardPress.dragged()) {
                    return;
                }
                if (!deleteMode) {
                    showWallpaperPage(lib.id);
                    return;
                }
                if (selectedIds.contains(lib.id)) {
                    selectedIds.remove(lib.id);
                } else {
                    selectedIds.add(lib.id);
                }
                notifyItemChanged(holder.getBindingAdapterPosition());
                updateLibDeleteToolbar();
            });
            // 长按行的空白/文字区 = 排序拖拽（ItemTouchHelper 的自动长按已关，这里手动起手）
            holder.card.setOnLongClickListener(v -> {
                if (deleteMode || showingWallpapers) {
                    return false;
                }
                libDragHelper.startDrag(holder);
                return true;
            });
            // 长按头像 = 拖出浮动头像去设范围；头像自己吃掉点击，所以点头像仍是进该库的壁纸页
            holder.imgThumb.setOnClickListener(v -> {
                if (holder.thumbPress.dragged()) {
                    return;
                }
                if (!deleteMode) {
                    showWallpaperPage(lib.id);
                }
            });
            holder.imgThumb.setOnLongClickListener(v -> {
                if (deleteMode) {
                    return false;
                }
                startAvatarDrag(holder, lib.id);
                return true;
            });
            // 头像吃掉 DOWN，所以这次手势的 MOVE/UP 都会回到这里 —— 浮动头像就靠它跟手
            holder.imgThumb.setOnTouchListener((v, event) -> {
                // 头像是可点的，这个监听会替它记账「按压有没有滑动过」
                holder.thumbPress.onTouch(v, event);
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    avatarLastRawX = event.getRawX();
                    avatarLastRawY = event.getRawY();
                } else if (action == MotionEvent.ACTION_MOVE && avatarFloat != null) {
                    avatarLastRawX = event.getRawX();
                    avatarLastRawY = event.getRawY();
                    moveAvatarFloat(avatarLastRawX, avatarLastRawY);
                } else if (avatarFloat != null && (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL)) {
                    finishAvatarDrag(action == MotionEvent.ACTION_UP,
                            event.getRawX(), event.getRawY());
                    return true;    // 拖过了就不算点击，别把壁纸页顶出来
                }
                return false;
            });
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }


    /**
     * 壁纸网格适配器（两列正方形）：点格直接进裁剪页重编；长按浮出铅笔（改标题小窗）/ 红垃圾桶
     * （直接删，不确认）两个圆形图标，点空白处或返回手势收起。
     */
    private class WallpaperAdapter extends RecyclerView.Adapter<WpHolder> {

        private List<WallpaperStore.Item> items = new ArrayList<>();
        // 长按浮出操作图标的格子下标（同一时间只允许一格；-1 = 没有）
        private int revealed = -1;

        // 缩略图内存缓存（上限 8MB）：网格一屏要绑 6~8 张，每次刷新都重新解码会掉帧。
        // v3.15 缩略图边长跟着屏幕（单张 0.5~1MB），上限跟着调大才够铺满可见格
        private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };

        /** 取缩略图：先查缓存，未命中再解码并存入。 */
        private Bitmap thumbFor(String id) {
            Bitmap cached = thumbs.get(id);
            if (cached != null) {
                return cached;
            }
            Bitmap decoded = WallpaperStore.getThumb(MainActivity.this, id);
            if (decoded != null) {
                thumbs.put(id, decoded);
            }
            return decoded;
        }

        void setItems(List<WallpaperStore.Item> newItems) {
            items = newItems;
            revealed = -1;
            notifyDataSetChanged();
        }

        void clearThumbs() {
            thumbs.evictAll();
        }

        /** 收起长按浮出的操作图标；返回是否有东西被收起（供返回键链路用）。 */
        boolean hideRevealed() {
            if (revealed < 0) {
                return false;
            }
            int old = revealed;
            revealed = -1;
            notifyItemChanged(old);
            return true;
        }

        @NonNull
        @Override
        public WpHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(MainActivity.this)
                    .inflate(R.layout.item_wallpaper, parent, false);
            return new WpHolder(view, pressSlopPx);
        }

        @Override
        public void onBindViewHolder(@NonNull WpHolder holder, int position) {
            final WallpaperStore.Item item = items.get(position);
            holder.imgThumb.setImageBitmap(thumbFor(item.id));
            // 格子下方显示壁纸标题（未命名则用占位文案）
            holder.tvTitle.setText(itemTitle(item));
            holder.actions.setVisibility(position == revealed ? View.VISIBLE : View.GONE);
            // 点格：有浮出图标时先收起（相当于取消），否则直接进裁剪页重编（源是原图，并复原上次取景框）
            holder.itemView.setOnClickListener(v -> {
                if (holder.press.dragged()) {
                    return;
                }
                if (hideRevealed()) {
                    return;
                }
                openEditItem(item);
            });
            // 长按浮出预览/删除图标
            holder.itemView.setOnLongClickListener(v -> {
                int old = revealed;
                revealed = position;
                if (old >= 0) {
                    notifyItemChanged(old);
                }
                notifyItemChanged(position);
                return true;
            });
            // 铅笔 → 改标题小窗（大图不显示：看构图归点格子进裁剪页，这里只给输入框和一行现场信息）
            holder.btnEdit.setOnClickListener(v -> {
                hideRevealed();
                showRenameDialog(item);
            });
            // 房子 → 把这张直接设为桌面（首页）壁纸
            holder.btnSetHome.setOnClickListener(v -> {
                hideRevealed();
                setAsHomeWallpaper(item);
            });
            // 红垃圾桶 → 直接删除，不再确认
            holder.btnDelete.setOnClickListener(v -> {
                revealed = -1;
                WallpaperStore.delete(MainActivity.this, item.id);
                refreshList();
                // 删掉的若是桌面/锁屏的当前壁纸，立刻清指针并推进到下一张上屏
                // （锁屏路径要解码 + setBitmap 系统调用，放后台线程；用 Application 上下文，
                //  线程可能在 Activity 销毁后才跑完）
                new Thread(() -> Switcher.reapplyIfCurrent(getApplicationContext(), item.libId, item.id),
                        "reapply-wallpaper").start();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    /** 库行视图持有者。 */
    private static class LibHolder extends RecyclerView.ViewHolder {

        final View card;
        final ImageView imgThumb;
        final TextView tvName;
        final EditText etName;
        final TextView tvCount;
        final View badgeHome;
        final View badgeLock;
        final CheckBox cbDelete;
        /** 行、头像、库名各一份按压记账：右滑开抽屉的起点可能落在这三者任一上。 */
        final PressGuard cardPress, thumbPress, namePress;

        LibHolder(@NonNull View itemView, int slopPx) {
            super(itemView);
            cardPress = new PressGuard(slopPx);
            thumbPress = new PressGuard(slopPx);
            namePress = new PressGuard(slopPx);
            card = itemView;
            imgThumb = itemView.findViewById(R.id.img_lib_thumb);
            tvName = itemView.findViewById(R.id.tv_lib_name);
            etName = itemView.findViewById(R.id.et_lib_name);
            tvCount = itemView.findViewById(R.id.tv_lib_count);
            badgeHome = itemView.findViewById(R.id.badge_lib_home);
            badgeLock = itemView.findViewById(R.id.badge_lib_lock);
            cbDelete = itemView.findViewById(R.id.cb_lib_delete);
            // 行的按压记账一次挂上就行（头像那份挂在它的头像监听回调里，见 onBindViewHolder）
            card.setOnTouchListener(cardPress);
            // 库名自己可点（就地改名），事件到不了行，所以单独记一笔
            tvName.setOnTouchListener(namePress);
        }
    }

    /** 壁纸格视图持有者。 */
    private static class WpHolder extends RecyclerView.ViewHolder {

        final ImageView imgThumb;
        final TextView tvTitle;
        final View actions;
        final View btnEdit;
        final View btnSetHome;
        final View btnDelete;
        /** 格子的按压记账：右滑（或任何滑动）后松手不该被当成「点开预览」。 */
        final PressGuard press;

        WpHolder(@NonNull View itemView, int slopPx) {
            super(itemView);
            press = new PressGuard(slopPx);
            imgThumb = itemView.findViewById(R.id.img_thumb);
            tvTitle = itemView.findViewById(R.id.tv_wallpaper_title);
            actions = itemView.findViewById(R.id.item_actions);
            btnEdit = itemView.findViewById(R.id.btn_edit);
            btnSetHome = itemView.findViewById(R.id.btn_set_home);
            btnDelete = itemView.findViewById(R.id.btn_delete);
            itemView.setOnTouchListener(press);
        }
    }

    /**
     * 「这次按压算不算点击」的记账器。
     * 普通可点的 View 只要 ACTION_UP 还落在自己范围内就 performClick，它不看手指移动了多少；
     * 而右滑开抽屉是旁听式手势（不抢事件），起点和终点常常都在同一行里 —— 结果抽屉拉开了，
     * 顺手还把那个库/预览页也打开了。这里在点击回调里先问一句「移动超过容差没有」。
     * 只旁听不改事件流（onTouch 返回 false），点击与长按的原有流程一律不动。
     */
    private static final class PressGuard implements View.OnTouchListener {

        private final int slopPx;
        private float downX, downY;
        private boolean dragged;

        PressGuard(int slopPx) {
            this.slopPx = slopPx;
        }

        boolean dragged() {
            return dragged;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downX = event.getRawX();
                downY = event.getRawY();
                dragged = false;
            } else if (action == MotionEvent.ACTION_MOVE && !dragged) {
                dragged = Math.abs(event.getRawX() - downX) > slopPx
                        || Math.abs(event.getRawY() - downY) > slopPx;
            }
            return false;
        }
    }
}

