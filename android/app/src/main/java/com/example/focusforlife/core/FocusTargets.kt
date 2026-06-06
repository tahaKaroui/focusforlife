package com.example.focusforlife.core

/**
 * Central place for blocked packages + domains so UI and services stay in sync.
 */
object FocusTargets {

    val blockedAppPackages: List<String> = listOf(
        "com.google.android.youtube",
        "com.google.android.youtube.tv",
        "com.google.android.apps.youtube.music",
        "com.google.android.apps.youtube.mango",
        "com.facebook.katana",
        "com.instagram.android",
        "com.snapchat.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.aweme",
        "com.ss.android.ugc.aweme.lite",
        "com.twitter.android",
        "com.netflix.mediaclient",
        "com.chess",
        "com.chess.chess",
        "air.com.chess.chess",
        "org.example.testapp",
        "org.example.testapp.dev",
        "com.example.testgame"
    )

    val blockedDomains: List<String> = listOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "youtu.be",
        "youtubei.googleapis.com",
        "googlevideo.com",
        "ytimg.com",
        "instagram.com",
        "www.instagram.com",
        "tiktok.com",
        "m.tiktok.com",
        "tiktokcdn.com",
        "site-c.example.com",
        "site-a.example.com",
        "www.site-a.example.com",
        "site-b.example.com",
        "www.site-b.example.com",
        "facebook.com",
        "www.facebook.com",
        "cloudflare-dns.com",
        "mozilla.cloudflare-dns.com",
        "dns.google",
        "dns.quad9.net",
        "dns.nextdns.io",
        "doh.opendns.com",
        "dns.adguard.com",
        "dnsforge.de"
    )

    val browserPackages: List<String> = listOf(
        "com.facebook.orca",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_dev",
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.opera.browser",
        "com.opera.browser.beta",
        "com.opera.mini.native",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.focus",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser",
        "com.vivaldi.browser",
        "com.duckduckgo.mobile.android",
        "com.ecosia.android",
        "com.google.android.apps.searchlite",
        "com.android.browser",
        "com.mi.globalbrowser"
    )

    val blockedAppSet: Set<String> = blockedAppPackages.toSet()
    val blockedDomainSet: Set<String> = blockedDomains.toSet()
    val browserPackageSet: Set<String> = browserPackages.toSet()

    fun normalizeDomain(raw: String): String =
        raw.trimEnd('.').lowercase()

    fun matchesBlockedDomain(raw: String): Boolean {
        val normalized = normalizeDomain(raw)
        return blockedDomainSet.any { normalized == it || normalized.endsWith(".$it") }
    }
}
