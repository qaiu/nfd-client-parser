package cn.qaiu.nfd.share;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns parser exceptions into one-line Chinese messages for the UI. */
public final class ShareErrors {

    private static final Pattern JSON_MESSAGE =
            Pattern.compile("\"(?:message|msg|info|errmsg|error_msg)\"\\s*:\\s*\"([^\"]{1,120})\"");
    private static final int MAX_LENGTH = 160;

    private ShareErrors() {
    }

    public static String friendly(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        ShareSession.ParseFailure both = find(error, ShareSession.ParseFailure.class);
        if (both != null) {
            return both.getMessage();
        }
        String all = collectMessages(error);
        String lower = all.toLowerCase(Locale.ROOT);
        if (has(error, UnknownHostException.class) || lower.contains("failed to resolve") || lower.contains("unknownhost")) {
            return "网络不可用或无法解析网盘域名";
        }
        if (has(error, TimeoutException.class) || lower.contains("timeout") || lower.contains("timed out") || all.contains("超时")) {
            return "解析超时，网盘服务器响应过慢，请稍后重试";
        }
        if (has(error, ConnectException.class) || lower.contains("connection refused")) {
            return "无法连接网盘服务器";
        }
        if (lower.contains("unsupported share url") || all.contains("不支持")) {
            return "暂不支持该分享链接";
        }
        if (all.contains("分享链接为空")) {
            return "请先粘贴分享链接";
        }
        String detail = providerMessage(all);
        if (!all.contains("提取码") && (all.contains("登录") || lower.contains("auths.")
                || lower.contains("refresh_token"))) {
            return "该网盘需要登录信息，请在「设置 → 网盘账号」中填写"
                    + (detail.isEmpty() ? "" : "：" + detail);
        }
        if (all.contains("密码") || all.contains("提取码") || lower.contains("pwd") || lower.contains("password")) {
            return "提取码错误或缺少提取码" + (detail.isEmpty() ? "" : "：" + detail);
        }
        if (all.contains("不存在") || all.contains("取消") || all.contains("失效") || all.contains("过期")
                || all.contains("违规") || all.contains("删除")) {
            return "分享已失效" + (detail.isEmpty() ? "" : "：" + detail);
        }
        if (!detail.isEmpty()) {
            return "解析失败：" + detail;
        }
        return "解析失败：" + shorten(rootMessage(error));
    }

    private static String providerMessage(String all) {
        Matcher matcher = JSON_MESSAGE.matcher(all);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String collectMessages(Throwable error) {
        StringBuilder out = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 10) {
            if (current.getMessage() != null) {
                out.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return out.toString();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        String message = null;
        int depth = 0;
        while (current != null && depth++ < 10) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        if (message == null) {
            return error.getClass().getSimpleName();
        }
        int marker = message.indexOf(" - ");
        return marker > 0 && marker < 80 ? message.substring(marker + 3) : message;
    }

    private static String shorten(String message) {
        String single = message.replaceAll("\\s+", " ").trim();
        return single.length() > MAX_LENGTH ? single.substring(0, MAX_LENGTH) + "…" : single;
    }

    private static boolean has(Throwable error, Class<? extends Throwable> type) {
        return find(error, type) != null;
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 10) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
