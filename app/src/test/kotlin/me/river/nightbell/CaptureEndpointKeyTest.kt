package me.river.nightbell

import me.river.nightbell.data.capture.capturedEndpointKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression for "one host, several interfaces, all folded into one row".
 * The list used to key on method + path only, so two interfaces sharing a host
 * (or sharing a path and differing only in a business query parameter) became
 * a single row that could not be tested on its own.
 */
class CaptureEndpointKeyTest {

    @Test
    fun `different paths on the same host stay separate interfaces`() {
        val health = capturedEndpointKey("GET", "https", "api.test", 443, "/health")
        val orders = capturedEndpointKey("GET", "https", "api.test", 443, "/orders")
        assertNotEquals(health, orders)
    }

    @Test
    fun `a different business parameter is still a different interface`() {
        val list = capturedEndpointKey("GET", "http", "api.test", 80, "/api?action=list")
        val detail = capturedEndpointKey("GET", "http", "api.test", 80, "/api?action=detail&id=7")
        assertNotEquals(list, detail)
    }

    @Test
    fun `no query value is normalised away - every request stays selectable`() {
        val first = capturedEndpointKey(
            "GET", "https", "api.test", 443, "/api?action=list&token=aaa&_t=1&page=1",
        )
        val second = capturedEndpointKey(
            "GET", "https", "api.test", 443, "/api?action=list&token=bbb&_t=2&page=2",
        )
        assertNotEquals(first, second)
        // An identical repeat still folds into one row and just counts a hit.
        assertEquals(first, capturedEndpointKey(
            "GET", "https", "api.test", 443, "/api?action=list&token=aaa&_t=1&page=1",
        ))
    }

    @Test
    fun `a different POST body on the same URL is a different interface`() {
        val list = capturedEndpointKey("POST", "https", "api.test", 443, "/api", "{\"action\":\"list\"}")
        val detail = capturedEndpointKey("POST", "https", "api.test", 443, "/api", "{\"action\":\"detail\"}")
        assertNotEquals(list, detail)
        assertEquals(
            list,
            capturedEndpointKey("POST", "https", "api.test", 443, "/api", "{\"action\":\"list\"}"),
        )
    }

    @Test
    fun `method and port are part of the identity`() {
        val base = capturedEndpointKey("GET", "http", "api.test", 8080, "/api")
        assertNotEquals(base, capturedEndpointKey("POST", "http", "api.test", 8080, "/api"))
        assertNotEquals(base, capturedEndpointKey("GET", "http", "api.test", 8081, "/api"))
        assertEquals("GET http://api.test:8080/api", base)
    }
}
