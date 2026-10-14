package com.fanjv.netproxy.platform;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.IBinder;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class Main {
    public static final String MANAGER_PACKAGE = "com.fanjv.netproxy";
    public static final String MANAGER_CERTIFICATE_SHA256 = "723e324ffc0564c20f51557fde05287d3b8695fa84a53da60b69154fa8458813";
    private static final String AUTHORITY = MANAGER_PACKAGE + ".connection";
    private static final int INPUT_LIMIT = 64 * 1024;

    public static void main(String[] args) {
        JSONObject response = new JSONObject();
        boolean success = false;
        try {
            response.put("schema", 1);
            response.put("ok", false);
            response.put("code", "android.query_failed");
            response.put("message", "Android 平台查询失败");
            if (Process.myUid() != 0) throw new SecurityException();
            JSONObject request = new JSONObject(readInput());
            Looper.prepareMainLooper();
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("systemMain").invoke(null);
            Context context = (Context) threadClass.getMethod("getSystemContext").invoke(thread);
            String operation = request.getString("operation");
            Object result;
            if ("users".equals(operation)) {
                result = users(context);
            } else if ("apps".equals(operation) || "icons".equals(operation)) {
                int user = request.getInt("user_id");
                if (user < 0 || user > 21474) throw new IllegalArgumentException();
                Context userContext = (Context) Context.class.getMethod("createContextAsUser", UserHandle.class, int.class)
                    .invoke(context, UserHandle.getUserHandleForUid(user * 100000), 0);
                result = "apps".equals(operation) ? apps(userContext, user) : icons(userContext, request);
            } else if ("provision".equals(operation)) {
                result = provision(context, request.getJSONObject("connection"));
            } else {
                throw new IllegalArgumentException();
            }
            response.put("ok", true);
            response.put("code", "android." + operation);
            response.put("message", "Android 平台查询完成");
            response.put("data", result);
            success = true;
        } catch (Throwable error) {
            // 系统异常可能包含输入资料，禁止将堆栈或异常文本写到输出。
        }
        System.out.println(response.toString());
        if (!success) System.exit(1);
    }

    private static String readInput() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int size;
        while ((size = System.in.read(buffer)) != -1) {
            if (output.size() + size > INPUT_LIMIT) throw new IllegalArgumentException();
            output.write(buffer, 0, size);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static JSONArray users(Context context) throws Exception {
        UserManager manager = context.getSystemService(UserManager.class);
        List<?> users = (List<?>) UserManager.class.getMethod("getUsers").invoke(manager);
        JSONArray result = new JSONArray();
        for (Object user : users) {
            Class<?> type = user.getClass();
            result.put(new JSONObject().put("id", type.getField("id").getInt(user))
                .put("name", (String) type.getField("name").get(user)));
        }
        return result;
    }

    private static JSONObject apps(Context context, int user) throws Exception {
        PackageManager manager = context.getPackageManager();
        List<ApplicationInfo> applications = new ArrayList<>(manager.getInstalledApplications(0));
        Collections.sort(applications, new Comparator<ApplicationInfo>() {
            @Override public int compare(ApplicationInfo first, ApplicationInfo second) {
                return first.packageName.compareTo(second.packageName);
            }
        });
        JSONArray result = new JSONArray();
        for (ApplicationInfo info : applications) {
            PackageInfo installed;
            try {
                installed = manager.getPackageInfo(info.packageName, 0);
            } catch (PackageManager.NameNotFoundException removed) {
                continue;
            }
            long version = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
            result.put(new JSONObject().put("package_name", info.packageName).put("uid", info.uid)
                .put("label", manager.getApplicationLabel(info).toString())
                .put("system", (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                .put("version_code", version).put("last_update_time", installed.lastUpdateTime));
        }
        String resources = context.getResources().getConfiguration().toString();
        String revision = digest((result.toString() + resources).getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("user_id", user).put("revision", revision).put("apps", result);
    }

    private static JSONArray icons(Context context, JSONObject request) throws Exception {
        JSONArray packages = request.getJSONArray("packages");
        int size = request.getInt("size");
        if (packages.length() == 0 || packages.length() > 32 || size < 16 || size > 192) throw new IllegalArgumentException();
        PackageManager manager = context.getPackageManager();
        JSONArray result = new JSONArray();
        for (int index = 0; index < packages.length(); index++) {
            String name = packages.getString(index);
            JSONObject entry = new JSONObject().put("package_name", name);
            Bitmap image = null;
            try {
                ApplicationInfo info = manager.getApplicationInfo(name, 0);
                // loadIcon 的厂商主题扩展会访问仅注册应用进程可用的 Provider，直接读取原生图标资源。
                Drawable drawable = info.icon == 0 ? manager.getDefaultActivityIcon()
                    : manager.getResourcesForApplication(info).getDrawable(info.icon, null);
                image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                drawable.setBounds(0, 0, size, size);
                drawable.draw(new Canvas(image));
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                if (!image.compress(Bitmap.CompressFormat.PNG, 100, bytes)) throw new IllegalStateException();
                entry.put("png", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP));
            } catch (PackageManager.NameNotFoundException missing) {
                entry.put("missing", true);
            } finally {
                if (image != null) image.recycle();
            }
            result.put(entry);
        }
        return result;
    }

    private static ProviderInfo verifyManager(PackageManager manager) throws Exception {
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo installed = manager.getPackageInfo(MANAGER_PACKAGE, flags);
        if (installed.applicationInfo == null || installed.applicationInfo.uid / 100000 != 0) throw new SecurityException();
        String[] packages = manager.getPackagesForUid(installed.applicationInfo.uid);
        if (packages == null || packages.length != 1 || !MANAGER_PACKAGE.equals(packages[0])) throw new SecurityException();
        Signature[] signers = Build.VERSION.SDK_INT >= 28 ? installed.signingInfo.getApkContentsSigners() : installed.signatures;
        if (signers == null || signers.length != 1 || !MANAGER_CERTIFICATE_SHA256.equals(digest(signers[0].toByteArray()))) throw new SecurityException();
        ProviderInfo provider = manager.resolveContentProvider(AUTHORITY, 0);
        if (provider == null || !MANAGER_PACKAGE.equals(provider.packageName)
            || provider.applicationInfo.uid != installed.applicationInfo.uid || provider.exported) throw new SecurityException();
        return provider;
    }

    private static JSONObject provision(Context context, JSONObject connection) throws Exception {
        PackageManager manager = context.getPackageManager();
        verifyManager(manager);
        Class<?> activityType = Class.forName("android.app.IActivityManager");
        Object activity = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        IBinder token = new Binder();
        // app_process 不在应用进程表中，必须使用系统外部 Provider 入口，并显式归还引用。
        Object holder = Build.VERSION.SDK_INT >= 29
            ? activityType.getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
                .invoke(activity, AUTHORITY, 0, token, "NetProxy")
            : activityType.getMethod("getContentProviderExternal", String.class, int.class, IBinder.class)
                .invoke(activity, AUTHORITY, 0, token);
        if (holder == null) throw new IllegalStateException();
        try {
            ProviderInfo expected = verifyManager(manager);
            ProviderInfo bound = (ProviderInfo) holder.getClass().getField("info").get(holder);
            if (!MANAGER_PACKAGE.equals(bound.packageName) || bound.exported
                || bound.applicationInfo.uid != expected.applicationInfo.uid) throw new SecurityException();
            Object remote = holder.getClass().getField("provider").get(holder);
            Class<?> providerType = Class.forName("android.content.IContentProvider");
            ContentProviderClient client = Build.VERSION.SDK_INT >= 29
                ? (ContentProviderClient) ContentProviderClient.class.getConstructor(ContentResolver.class, providerType, String.class, boolean.class)
                    .newInstance(context.getContentResolver(), remote, AUTHORITY, true)
                : (ContentProviderClient) ContentProviderClient.class.getDeclaredConstructor(ContentResolver.class, providerType, boolean.class)
                    .newInstance(context.getContentResolver(), remote, true);
            try (ContentProviderClient provider = client) {
                Bundle extras = new Bundle();
                extras.putString("connection", connection.toString());
                Bundle reply = provider.call("provision", null, extras);
                if (reply == null || !reply.getBoolean("accepted")) throw new IllegalStateException();
            }
        } finally {
            if (Build.VERSION.SDK_INT >= 29) {
                activityType.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                    .invoke(activity, AUTHORITY, token, 0);
            } else {
                activityType.getMethod("removeContentProviderExternal", String.class, IBinder.class)
                    .invoke(activity, AUTHORITY, token);
            }
        }
        return new JSONObject().put("delivered", true);
    }

    private static String digest(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder text = new StringBuilder(64);
        for (byte value : hash) {
            text.append(Character.forDigit((value >>> 4) & 15, 16));
            text.append(Character.forDigit(value & 15, 16));
        }
        return text.toString();
    }
}
