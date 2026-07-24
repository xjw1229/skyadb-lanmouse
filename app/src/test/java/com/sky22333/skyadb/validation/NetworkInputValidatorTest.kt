package com.sky22333.skyadb.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkInputValidatorTest {
    @Test
    fun ipv4Error_acceptsValidIpv4() {
        assertNull(NetworkInputValidator.ipv4Error("192.168.1.86"))
        assertNull(NetworkInputValidator.ipv4Error("0.0.0.0"))
        assertNull(NetworkInputValidator.ipv4Error("255.255.255.255"))
    }

    @Test
    fun ipv4Error_rejectsInvalidIpv4() {
        assertEquals("请输入正确的 IPv4 地址", NetworkInputValidator.ipv4Error("192.168.1.999"))
        assertEquals("请输入正确的 IPv4 地址", NetworkInputValidator.ipv4Error("192.168.1"))
        assertEquals("请输入正确的 IPv4 地址", NetworkInputValidator.ipv4Error("example.com"))
    }

    @Test
    fun portError_acceptsValidPort() {
        assertNull(NetworkInputValidator.portError("1"))
        assertNull(NetworkInputValidator.portError("5555"))
        assertNull(NetworkInputValidator.portError("65535"))
    }

    @Test
    fun portError_rejectsInvalidPort() {
        assertEquals("端口只能填写数字", NetworkInputValidator.portError("abc"))
        assertEquals("端口范围应为 1-65535", NetworkInputValidator.portError("0"))
        assertEquals("端口范围应为 1-65535", NetworkInputValidator.portError("65536"))
        assertEquals("配对端口范围应为 1-65535", NetworkInputValidator.portError("70000", label = "配对端口"))
    }
}
