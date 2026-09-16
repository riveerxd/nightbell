package me.river.nightbell

/**
 * Real payloads, shared by the JVM suite and the on-device one.
 *
 * Copied off real endpoints rather than invented: the scientific notation, the
 * `# EOF` terminator, the 200-with-status-error envelope and Alertmanager's
 * `status` block are all things that were awkward in the wild first, and a
 * fixture that tidied them up would stop testing the awkward part.
 *
 * Here rather than in one test class because both source sets need them and two
 * copies drift.
 */
object PrometheusPayloads {

    /** A node_exporter scrape, trimmed. `node_after_eof` is the trap. */
    val NODE_EXPORTER = """
        # HELP node_load1 1m load average.
        # TYPE node_load1 gauge
        node_load1 0.29
        # HELP node_cpu_seconds_total Seconds the CPUs spent in each mode.
        # TYPE node_cpu_seconds_total counter
        node_cpu_seconds_total{cpu="0",mode="idle"} 137464.2
        node_cpu_seconds_total{cpu="0",mode="system"} 1204.51
        node_cpu_seconds_total{cpu="1",mode="idle"} 137301.09
        # HELP node_filesystem_avail_bytes Filesystem space available in bytes.
        # TYPE node_filesystem_avail_bytes gauge
        node_filesystem_avail_bytes{device="/dev/sda1",fstype="ext4",mountpoint="/"} 4.0763392e+10
        # HELP node_memory_MemAvailable_bytes Memory available in bytes.
        # TYPE node_memory_MemAvailable_bytes gauge
        node_memory_MemAvailable_bytes 3.4359738368e+09
        # EOF
        node_after_eof 999
    """.trimIndent()

    /** The same host under load, for the failing half of a test. */
    val NODE_EXPORTER_HOT = NODE_EXPORTER.replace("node_load1 0.29", "node_load1 7.42")

    const val EMPTY_VECTOR =
        """{"status":"success","data":{"resultType":"vector","result":[]}}"""

    const val DOWN_VECTOR = """
        {"status":"success","data":{"resultType":"vector","result":[
          {"metric":{"__name__":"up","instance":"api:9090","job":"api"},
           "value":[1717171717.171,"0"]}
        ]}}
    """

    const val QUERY_ERROR = """
        {"status":"error","errorType":"bad_data",
         "error":"invalid parameter \"query\": 1:5: parse error: unexpected end of input"}
    """

    const val MATRIX = """
        {"status":"success","data":{"resultType":"matrix","result":[
          {"metric":{"__name__":"queue_depth","job":"worker"},
           "values":[[1717171700,"1"],[1717171710,"2"],[1717171720,"3"]]}
        ]}}
    """

    const val SCALAR =
        """{"status":"success","data":{"resultType":"scalar","result":[1717171717.171,"4"]}}"""

    /** Three alerts: one critical, one warning, one already silenced. */
    const val ALERTS = """
        [
          {"labels":{"alertname":"DiskFillingUp","severity":"critical","team":"storage","instance":"db01"},
           "annotations":{"summary":"Disk will fill in 4 hours"},
           "status":{"state":"active","silencedBy":[],"inhibitedBy":[]},
           "startsAt":"2026-09-14T18:00:00.000Z","fingerprint":"a1b2"},
          {"labels":{"alertname":"HighLatency","severity":"warning","team":"api"},
           "annotations":{"summary":"p99 over 2s for 10 minutes"},
           "status":{"state":"active","silencedBy":[],"inhibitedBy":[]},
           "startsAt":"2026-09-14T18:30:00.000Z","fingerprint":"c3d4"},
          {"labels":{"alertname":"SilencedNoise","severity":"critical","team":"api"},
           "annotations":{"summary":"Known and muted"},
           "status":{"state":"suppressed","silencedBy":["sil-1"],"inhibitedBy":[]},
           "startsAt":"2026-09-14T10:00:00.000Z","fingerprint":"e5f6"}
        ]
    """
}
