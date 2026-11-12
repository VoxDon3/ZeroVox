package com.zerovox

import android.content.Context
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket

object AgentLoader {
    private const val ELF_PORT = 9021
    private const val AGENT_PORT = 33333
    private const val ASSET_NAME = "zerovox-agent.elf"
    private const val CONNECT_TIMEOUT_MS = 3000
    private const val READY_WAIT_MS = 9000

    /** يرجع true إذا كان الوكيل مجهزاً (المنفذ 33333 مستجيب). */
    fun isAgentUp(host: String): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, AGENT_PORT), CONNECT_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

    /** يرفع البيلود من الأصول عبر elfldr (المنفذ 9021) إذا كان الجهاز مكسور الحماية والفلودر شغال. */
    fun inject(context: Context, host: String): Boolean {
        val elf = context.assets.open(ASSET_NAME).use { it.readBytes() }
        Socket().use { s ->
            s.connect(InetSocketAddress(host, ELF_PORT), CONNECT_TIMEOUT_MS)
            s.soTimeout = CONNECT_TIMEOUT_MS
            s.getOutputStream().apply { write(elf); flush() }
        }
        return true
    }

    /** يضمن تشغيل الوكيل: إذا كان شاغلاً يرجع true، وإلا يحاول الحقن ثم ينتظر حتى يرفع. */
    fun ensureRunning(context: Context, host: String): Boolean {
        if (isAgentUp(host)) return true
        return runCatching {
            inject(context, host)
            val deadline = System.currentTimeMillis() + READY_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                if (isAgentUp(host)) return true
                Thread.sleep(300)
            }
            isAgentUp(host)
        }.getOrDefault(false)
    }
}
