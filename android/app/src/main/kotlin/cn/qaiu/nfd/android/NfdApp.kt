package cn.qaiu.nfd.android

import android.app.Application
import cn.qaiu.nfd.android.download.DownloadController

class NfdApp : Application() {
    lateinit var downloads: DownloadController
        private set

    override fun onCreate() {
        super.onCreate()
        downloads = DownloadController(this)
    }
}
