package me.river.nightbell.data.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthTdscdma
import android.telephony.CellSignalStrengthWcdma
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import me.river.nightbell.data.capture.CaptureRepository
import java.net.Inet4Address

/** One interface, as reported by LinkProperties. */
data class AddressFact(val text: String, val ipv4: Boolean)

/** A single serving/neighbour cell's signal reading. */
data class CellFact(
    val kind: String,
    val dbm: Int?,
    val level: Int?,
    val asu: Int?,
    val details: List<String>,
)

data class WifiFact(
    val ssid: String?,
    val bssid: String?,
    val rssi: Int?,
    val level: Int?,
    val maxLevel: Int,
    val linkSpeedMbps: Int?,
    val rxLinkSpeedMbps: Int?,
    val txLinkSpeedMbps: Int?,
    val frequencyMhz: Int,
    val channel: Int?,
    val band: String,
    val standard: String,
    val enabled: Boolean,
    val dhcp: List<String>,
)

data class CellularFact(
    val operator: String?,
    val simOperator: String?,
    val mccMnc: String?,
    val simCountry: String?,
    val simState: String,
    val roaming: Boolean?,
    val dataNetworkType: String?,
    val signalDbm: Int?,
    val signalLevel: Int?,
    val cells: List<CellFact>,
)

data class NetworkFact(val id: String, val transports: Set<String>, val validated: Boolean)

/**
 * Everything the phone knows about its current connection. Deliberably a plain
 * snapshot rather than a flow: nearly all of these values live behind system
 * services that have no listener for a non-system app, so the screen samples
 * them on a timer. Labels stay language-neutral here; the screen chooses the
 * wording with L10n.pick() so a language switch needs no re-read.
 */
data class NetworkSnapshot(
    val capturedAt: Long = System.currentTimeMillis(),
    val connected: Boolean = false,
    val transports: Set<String> = emptySet(),
    val capabilities: Set<String> = emptySet(),
    val downstreamKbps: Int? = null,
    val upstreamKbps: Int? = null,
    val cellularSignalStrength: Int? = null,
    val addresses: List<AddressFact> = emptyList(),
    val dnsServers: List<String> = emptyList(),
    val gateways: List<String> = emptyList(),
    val routes: List<String> = emptyList(),
    val interfaceName: String? = null,
    val mtu: Int? = null,
    val privateDns: String? = null,
    val privateDnsActive: Boolean = false,
    val dhcpServer: String? = null,
    val wifi: WifiFact? = null,
    val cellular: CellularFact? = null,
    val totalRx: Long = 0,
    val totalTx: Long = 0,
    val mobileRx: Long = 0,
    val mobileTx: Long = 0,
    val rxPerSecond: Long = 0,
    val txPerSecond: Long = 0,
    val vpnActive: Boolean = false,
    val captureProxy: String? = null,
    val networks: List<NetworkFact> = emptyList(),
    val hasLocationPermission: Boolean = false,
    val telephonyAvailable: Boolean = false,
)

private data class TrafficSample(val at: Long, val rx: Long, val tx: Long)

/**
 * Reads platform network state. All of it stays on the device: no request is
 * made, no identifier leaves the process. Every system call is guarded because
 * OEMs throw SecurityException for telephony/Wi-Fi getters even when the
 * matching permission is in the manifest.
 */
@SuppressLint("MissingPermission")
object NetworkInfoReader {

    @Volatile
    private var lastTraffic: TrafficSample? = null

    fun read(context: Context): NetworkSnapshot {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val hasLocation = ContextCompat.checkSelfPermission(
            app, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val telephonyAvailable = app.packageManager
            .hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

        val active: Network? = cm?.let { runCatching { it.activeNetwork }.getOrNull() }
        val caps = active?.let { net ->
            cm?.let { runCatching { it.getNetworkCapabilities(net) }.getOrNull() }
        }
        val link = active?.let { net ->
            cm?.let { runCatching { it.getLinkProperties(net) }.getOrNull() }
        }
        val transports = transportsOf(caps)
        val allNetworks = cm?.let { runCatching { it.allNetworks }.getOrDefault(emptyArray()) }
            ?: emptyArray()

        val rx = runCatching { TrafficStats.getTotalRxBytes() }.getOrDefault(-1L)
            .coerceAtLeast(0)
        val tx = runCatching { TrafficStats.getTotalTxBytes() }.getOrDefault(-1L)
            .coerceAtLeast(0)
        val previous = lastTraffic
        val rxRate = previous?.let { bytesPerSecond(now, rx, it.at, it.rx) } ?: 0
        val txRate = previous?.let { bytesPerSecond(now, tx, it.at, it.tx) } ?: 0
        lastTraffic = TrafficSample(now, rx, tx)

        val capture = CaptureRepository.state.value

        return NetworkSnapshot(
            capturedAt = now,
            connected = active != null && caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            transports = transports,
            capabilities = capabilitiesOf(caps),
            downstreamKbps = caps?.linkDownstreamBandwidthKbps?.takeIf { it > 0 },
            upstreamKbps = caps?.linkUpstreamBandwidthKbps?.takeIf { it > 0 },
            cellularSignalStrength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                caps?.signalStrength?.takeIf { it != Int.MIN_VALUE }
            } else {
                null
            },
            addresses = link?.linkAddresses.orEmpty().map {
                AddressFact(
                    "${it.address?.hostAddress.orEmpty()}/${it.prefixLength}",
                    it.address is Inet4Address,
                )
            },
            dnsServers = link?.dnsServers.orEmpty().mapNotNull { it.hostAddress },
            gateways = link?.routes.orEmpty()
                .filter { it.isDefaultRoute }
                .mapNotNull { runCatching { it.gateway?.hostAddress }.getOrNull() }
                .distinct(),
            routes = link?.routes.orEmpty().take(12).mapNotNull { route ->
                val destination = runCatching { route.destination?.toString() }.getOrNull()
                val gateway = runCatching { route.gateway?.hostAddress }.getOrNull()
                listOfNotNull(destination, gateway?.let { "via $it" }).joinToString(" ")
                    .takeIf { it.isNotBlank() }
            },
            interfaceName = link?.interfaceName,
            mtu = link?.mtu?.takeIf { it > 0 },
            privateDns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { link?.privateDnsServerName }.getOrNull()
            } else {
                null
            },
            privateDnsActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { link?.isPrivateDnsActive == true }.getOrDefault(false)
            } else {
                false
            },
            dhcpServer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { link?.dhcpServerAddress?.hostAddress }.getOrNull()
            } else {
                null
            },
            wifi = readWifi(app, hasLocation),
            cellular = if (telephonyAvailable) readCellular(app, transports) else null,
            totalRx = rx,
            totalTx = tx,
            mobileRx = runCatching { TrafficStats.getMobileRxBytes() }.getOrDefault(-1L)
                .coerceAtLeast(0),
            mobileTx = runCatching { TrafficStats.getMobileTxBytes() }.getOrDefault(-1L)
                .coerceAtLeast(0),
            rxPerSecond = rxRate,
            txPerSecond = txRate,
            vpnActive = allNetworks.any { net ->
                runCatching {
                    cm?.getNetworkCapabilities(net)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }.getOrDefault(false)
            },
            captureProxy = if (capture.running && capture.proxyPort > 0) {
                "127.0.0.1:${capture.proxyPort}"
            } else {
                null
            },
            networks = allNetworks.mapNotNull { net ->
                val netCaps = runCatching { cm?.getNetworkCapabilities(net) }.getOrNull() ?: return@mapNotNull null
                NetworkFact(
                    id = net.toString(),
                    transports = transportsOf(netCaps),
                    validated = netCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                )
            },
            hasLocationPermission = hasLocation,
            telephonyAvailable = telephonyAvailable,
        )
    }

    private fun transportsOf(caps: NetworkCapabilities?): Set<String> = buildSet {
        if (caps == null) return@buildSet
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
        if (isEmpty()) add("other")
    }

    private fun capabilitiesOf(caps: NetworkCapabilities?): Set<String> = buildSet {
        if (caps == null) return@buildSet
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) add("internet")
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) add("validated")
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) add("captive_portal")
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) add("not_metered")
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) add("not_roaming")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        ) {
            add("not_suspended")
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission", "HardwareIds")
    private fun readWifi(app: Context, hasLocation: Boolean): WifiFact? {
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val info = runCatching { wifi.connectionInfo }.getOrNull() ?: return null
        val rssi = runCatching { info.rssi }.getOrDefault(-127).takeIf { it != -127 }
        val maxLevel = 4
        val level = if (rssi == null) {
            null
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { wifi.calculateSignalLevel(rssi) }.getOrElse { rssiGrade(rssi) }
                .coerceIn(0, maxLevel)
        } else {
            runCatching { WifiManager.calculateSignalLevel(rssi, maxLevel + 1) }
                .getOrElse { rssiGrade(rssi) }.coerceIn(0, maxLevel)
        }
        val frequency = runCatching { info.frequency }.getOrDefault(0)
        // Android 8+ hides SSID/BSSID unless location permission is granted and
        // location services are on. Show nothing rather than "<unknown ssid>".
        val ssid = if (hasLocation) {
            runCatching { info.ssid }
                .getOrNull()
                ?.removeSurrounding("\"")
                ?.takeUnless { it.isBlank() || it.contains("unknown", ignoreCase = true) }
        } else {
            null
        }
        val bssid = if (hasLocation) {
            runCatching { info.bssid }
                .getOrNull()
                ?.takeUnless { it.isBlank() || it.equals("02:00:00:00:00:00", ignoreCase = true) }
        } else {
            null
        }
        val dhcp = runCatching { wifi.dhcpInfo }.getOrNull()
        val dhcpRows = dhcp?.let {
            buildList {
                add("server ${intToIpv4(it.serverAddress)}")
                add("gateway ${intToIpv4(it.gateway)}")
                add("dns1 ${intToIpv4(it.dns1)}")
                add("dns2 ${intToIpv4(it.dns2)}")
                add("netmask ${intToIpv4(it.netmask)}")
                if (it.leaseDuration > 0) add("lease ${it.leaseDuration}s")
            }
        }.orEmpty()
        return WifiFact(
            ssid = ssid,
            bssid = bssid,
            rssi = rssi,
            level = level,
            maxLevel = maxLevel,
            linkSpeedMbps = runCatching { info.linkSpeed }.getOrDefault(-1).takeIf { it > 0 },
            rxLinkSpeedMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { info.rxLinkSpeedMbps }.getOrDefault(-1).takeIf { it > 0 }
            } else {
                null
            },
            txLinkSpeedMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { info.txLinkSpeedMbps }.getOrDefault(-1).takeIf { it > 0 }
            } else {
                null
            },
            frequencyMhz = frequency,
            channel = wifiChannel(frequency),
            band = wifiBand(frequency),
            standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wifiStandardLabel(runCatching { info.wifiStandard }.getOrDefault(0))
            } else {
                "-"
            },
            enabled = runCatching { wifi.isWifiEnabled }.getOrDefault(false),
            dhcp = dhcpRows,
        )
    }

    private fun readCellular(app: Context, transports: Set<String>): CellularFact? {
        val telephony = app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return null
        val strength: SignalStrength? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { telephony.signalStrength }.getOrNull()
        } else {
            null
        }
        val cells = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && strength != null) {
            runCatching { strength.cellSignalStrengths }.getOrDefault(emptyList())
                .map { describeCell(it) }
        } else {
            emptyList()
        }
        return CellularFact(
            operator = runCatching { telephony.networkOperatorName }.getOrNull()?.takeIf { it.isNotBlank() },
            simOperator = runCatching { telephony.simOperatorName }.getOrNull()?.takeIf { it.isNotBlank() },
            mccMnc = runCatching { telephony.simOperator }.getOrNull()?.takeIf { it.isNotBlank() },
            simCountry = runCatching { telephony.simCountryIso }.getOrNull()?.takeIf { it.isNotBlank() },
            simState = runCatching { simStateLabel(telephony.simState) }.getOrDefault("-"),
            roaming = runCatching { telephony.isNetworkRoaming }.getOrNull(),
            dataNetworkType = runCatching { telephony.dataNetworkType }
                .getOrNull()?.let(::dataNetworkTypeLabel),
            // TelephonyManager.signalStrength needs ACCESS_FINE_LOCATION; the
            // per-cell list above is likewise empty until the user grants it.
            signalDbm = cells.firstOrNull()?.dbm,
            signalLevel = if (strength != null && cells.isNotEmpty()) {
                runCatching { strength.level }.getOrNull()?.coerceIn(0, 4)
            } else {
                null
            },
            cells = if ("cellular" in transports || cells.isNotEmpty()) cells else emptyList(),
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun describeCell(cell: CellSignalStrength): CellFact {
        val kind = when (cell) {
            is CellSignalStrengthNr -> "5G NR"
            is CellSignalStrengthLte -> "LTE"
            is CellSignalStrengthWcdma -> "WCDMA"
            is CellSignalStrengthGsm -> "GSM"
            is CellSignalStrengthTdscdma -> "TD-SCDMA"
            else -> "Cell"
        }
        val details = buildList {
            if (cell is CellSignalStrengthLte) {
                add("RSRP ${cell.rsrp}")
                add("RSRQ ${cell.rsrq}")
                add("SINR ${cell.rssnr}")
                add("CQI ${cell.cqi}")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is CellSignalStrengthNr) {
                add("SS-RSRP ${cell.ssRsrp}")
                add("SS-RSRQ ${cell.ssRsrq}")
                add("SS-SINR ${cell.ssSinr}")
            }
        }
        return CellFact(
            kind = kind,
            dbm = runCatching { cell.dbm }.getOrNull(),
            level = runCatching { cell.level }.getOrNull()?.coerceIn(0, 4),
            asu = runCatching { cell.asuLevel }.getOrNull(),
            details = details,
        )
    }

    private fun simStateLabel(state: Int): String = when (state) {
        TelephonyManager.SIM_STATE_ABSENT -> "Absent"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Network locked"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
        TelephonyManager.SIM_STATE_READY -> "Ready"
        TelephonyManager.SIM_STATE_NOT_READY -> "Not ready"
        TelephonyManager.SIM_STATE_PERM_DISABLED -> "Disabled"
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "Card error"
        else -> "-"
    }
}
