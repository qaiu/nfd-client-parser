package cn.qaiu.nfd.desktop;

import cn.qaiu.nfd.share.ShareSession;

/**
 * Classpath smoke check that does not start JavaFX or contact a share host.
 */
public final class Check {

    private Check() {
    }

    public static void main(String[] args) {
        ShareSession session = ShareSession.open("https://pan.quark.cn/s/abcdef", null);
        if (!"qk".equals(session.type())) {
            throw new IllegalStateException("parser recognition failed: " + session.type());
        }
        System.out.println("nfd-desktop check ok: " + session.panName() + " / " + session.type());
    }
}
