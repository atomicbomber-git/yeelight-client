import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.output.TermUi
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.*

class Main : CliktCommand() {
    private val targetPort = 1982
    private val multicastGroup = "239.255.255.250"
    private val lightBulbs = hashMapOf<String, LightBulb>()

    private val inDebugMode: Boolean by option(help = "Show debug logs.").flag("--debug", "-d", default = false)
    private val searchIntervalMillis: Long by option(help = "Search interval in milliseconds.").long().default(4000)

    @ExperimentalStdlibApi
    private val bulbSearchMessage = """
        M-SEARCH * HTTP/1.1
        HOST: 239.255.255.250:1982
        MAN: "ssdp:discover"
        ST: wifi_bulb
    """
        .trimIndent()
        .replace("\n", "\r\n")
        .encodeToByteArray()

    private val sendingSocket = MulticastSocket()

    private val buffer: Array<Byte> = Array(2048) {
        0.toByte()
    }

    private val receivedDatagramPacket = DatagramPacket(buffer.toByteArray(), buffer.size)

    @ExperimentalStdlibApi
    private fun performReceivingTask() {
        while (true) {
            sendingSocket.receive(receivedDatagramPacket)

            receivedDatagramPacket.data.decodeToString()
                .split("\n")
                .drop(4)
                .dropLast(1)
                .fold(HashMap<String, String>()) { attributeMap, line ->
                    attributeMap.also {
                        it[line.substringBefore(":").trim().toLowerCase()] =
                            line.substringAfter(":").trim().toLowerCase()
                    }
                }.let { attributeMap ->
                    try {
                        LightBulb(
                            location = checkNotNull(attributeMap["location"]),
                            server = checkNotNull(attributeMap["server"]),
                            id = checkNotNull(attributeMap["id"]),
                            model = checkNotNull(attributeMap["model"]),
                            fw_ver = checkNotNull(attributeMap["fw_ver"]),
                            support = checkNotNull(attributeMap["support"]?.split(" ")?.toTypedArray()),
                            power = checkNotNull(attributeMap["power"]),
                            bright = checkNotNull(attributeMap["bright"]),
                            color_mode = checkNotNull(attributeMap["color_mode"]),
                            ct = checkNotNull(attributeMap["ct"]?.toIntOrNull()),
                            rgb = checkNotNull(attributeMap["rgb"]?.toIntOrNull()),
                            hue = checkNotNull(attributeMap["hue"]?.toIntOrNull()),
                            sat = checkNotNull(attributeMap["sat"]?.toIntOrNull()),
                            name = checkNotNull(attributeMap["name"])
                        )
                    } catch (error: IllegalStateException) {
                        null
                    }
                }?.let { lightBulb ->
                    this.lightBulbs[lightBulb.id] = lightBulb
                }
        }
    }

    @ExperimentalStdlibApi
    private suspend fun performSendingTask() {
        while (true) {
            log("Sending data...")
            sendBulbSearchMessage()
            log("Finished sending data...")
            delay(this.searchIntervalMillis)
        }
    }

    @ExperimentalStdlibApi
    private fun sendBulbSearchMessage() {
        sendingSocket.send(
            DatagramPacket(
                bulbSearchMessage,
                bulbSearchMessage.size,
                InetAddress.getByName(multicastGroup),
                targetPort
            )
        )
    }

    private fun log(text: String) {
        if (inDebugMode) {
            TermUi.echo(text)
        }
    }

    @ExperimentalStdlibApi
    override fun run() {
        runBlocking(Dispatchers.IO) {
            listOf(
                launch { performSendingTask() },
                launch { performReceivingTask() }
            ).joinAll()
        }
    }
}

data class LightBulb(
    val location: String, /*Location: yeelight://192.168.1.9:55443*/
    val server: String, /*Server: POSIX UPnP/1.0 YGLC/1*/
    val id: String, /*id: 0x00000000124d7643*/
    val model: String, /*model: color4*/
    val fw_ver: String, /*fw_ver: 18*/
    val support: Array<String>, /*support: get_prop set_default set_power toggle set_bright set_scene cron_add cron_get cron_del start_cf stop_cf set_ct_abx adjust_ct set_name set_adjust adjust_bright set_rgb set_hsv set_music set_wrgb*/
    val power: String, /*power: on*/
    val bright: String, /*bright: 5*/
    val color_mode: String, /*color_mode: 2*/
    val ct: Int, /*ct: 5307*/
    val rgb: Int, /*rgb: 16737792*/
    val hue: Int, /*hue: 24*/
    val sat: Int, /*sat: 100*/
    val name: String /*name:*/
)

@ExperimentalStdlibApi
fun main(args: Array<String>) = Main().main(args)
