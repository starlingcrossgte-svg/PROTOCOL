package com.protocol.app.protocol

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// How To Use subpage. Content only: no state, no transport.
//
// House style: prose in Para (real paragraphs, do not chop into steps), two
// column material in RefTable, short points in Bullets. No dashes, no colons,
// no semicolons. ECM/TCM, and "reading and writing the ROM" not "flashing".
//
// Describe only controls that exist — check the composable first. There is no
// READ LIVE DATA button; the tap loop is the only way to start a read.

private const val CONTACT_HANDLE = "starling cross on Facebook"

@Composable
internal fun NavigationBody() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        CategoryHeader("MOVING AROUND")
        Para(
            "Home is the middle page. Swipe left for DTC Scan, swipe right for Live Data. " +
                "Every other function opens from a button on Home."
        )
        Para(
            "Keep in mind some of the configurations or settings are split. Under the Configuration " +
                "page, you will find the option to select CSV output. However, to upload logger " +
                "definitions, ECM definitions, or ROM files, you will need to navigate to the " +
                "sub page of the corresponding function."
        )

        CategoryHeader("LETS GET TO IT")
        Para(
            "From the main page with the PROTOCOL logo at the top, select Configuration. Pick your " +
                "ADAPTER, then the PROTOCOL. This is the communication method of your ECM. " +
                "This setting, along with the adapter, will use an initiation that is specific " +
                "to the combination you choose."
        )
        Para(
            "Next, select your POLLING method. There are 3 methods, each serving different " +
                "purposes."
        )
        Para(
            "\"Poll\" is your standard 1 request, 1 response round trip. This method is the " +
                "slowest, but it is the only method that allows you to collect data from both " +
                "the ECM and TCM at the same time."
        )
        Para(
            "Next, we have \"Stream.\" Here you send one request and the ECM then keeps " +
                "sending replies on its own until you stop it, instead of waiting to be asked " +
                "each time. It is much faster, but it is K Line only and it reads the ECM " +
                "only. There are multiple styles of this depending on the adapter you have. " +
                "Last there is \"Monitor.\" This method uses an adapter's CAN bus sniffing " +
                "ability. It listens to a broadcast bus and never transmits, so it needs " +
                "PROTOCOL set to CAN Broadcast, and it feeds the log rather than the gauges. " +
                "Both OBDLink adapters and the OpenPort can do it. Any method your adapter and " +
                "bus combination cannot run is greyed out, so the list only offers what will " +
                "actually work. Once you have selected your combination, plug the adapter in " +
                "and accept the USB permission prompt. If you are using a Bluetooth adapter you " +
                "will need to pair with the adapter in Android's Bluetooth settings first."
        )
        Para(
            "Poll interval is a delay between the time you receive a response and the time you " +
                "send the next request. This setting only applies to the standard Poll method."
        )
        Para("If you wish, choose a background picture.")
        Para(
            "Next, you will select a folder location for your logs. You must select a folder " +
                "to use the auto save feature while logging. If you do not, you will need to " +
                "manually save each file."
        )
        Para(
            "If you choose to name the logs, they will automatically enumerate every time a " +
                "log is saved or exported. For example, if you name your CSV log \"sdrev,\" " +
                "when the log saves, it will save as \"sdrev1,\" \"sdrev2,\" and so on."
        )
        Para(
            "After you finish the main configuration, keep in mind that your selections will " +
                "persist unless you delete the app or clear the app data and cache."
        )

        CategoryHeader("SETTING UP A LOG")
        Para(
            "To start logging, swipe right from the home screen, click the hamburger menu, and " +
                "select ECM Parameters. Inside this page, you will see a button at the bottom. " +
                "This is how you upload your logger definition files. If you upload the correct " +
                "file, you will instantly see your logging parameters. Go ahead and select what " +
                "you would like to monitor. A green checkmark will confirm your selection. " +
                "Then, navigate back out of the screen and menu to see the gauges. You can " +
                "resize the gauges by long pressing. The borders will illuminate a bright " +
                "white, and an \"x\" will appear in the middle. Drag the edges to your desired " +
                "size, and to remove a gauge, tap the \"x.\" You can also resize the log window " +
                "at the bottom edge. Once you have the perfect layout, plug the adapter into " +
                "your diagnostics port if not already."
        )

        CategoryHeader("LOCK AND TAP")
        Para(
            "To officially kick off a log, you will need to enable the \"lock and tap\" " +
                "feature. This feature operates on a touch loop and allows you to tap anywhere " +
                "on the screen."
        )
        Para(
            "You will see the sides of the screen and the borders of your gauges flash three " +
                "times. This means the screen lock and tap loop is active. To exit the screen " +
                "lock, tap the \"cancel lock and tap\" button. When this feature is enabled, " +
                "the first tap will start requesting addresses from your ECM, and the live data " +
                "will display in the individual gauges. Tap the screen again to also collect a " +
                "log. The third tap will stop the log and polling connection. When this " +
                "happens, you will be met with a prompt labeled \"cancel auto save\". If you " +
                "tap this option, the log will not save. You have the choice to clear the log " +
                "or manually export the log. If you wish to expand the log and interact with " +
                "the data, you must cancel the lock and tap feature and then expand the log. " +
                "This will allow you to scroll the log as well as highlight text to copy " +
                "snippets. Now, if you do not cancel the auto save feature, the log will be " +
                "saved to the output location you chose when you completed the main " +
                "configuration. If you do not cancel the screen lock, the tap loop is still " +
                "active. Tap anywhere on the screen to start the loop again or to exit at any " +
                "time by tapping the \"cancel lock and tap\" button or using Android navigation " +
                "buttons at the bottom of the screen."
        )

        CategoryHeader("INDICATORS")
        Para(
            "The app reports what it is doing with light rather than text. These are all of " +
                "the signals."
        )
        RefTable(
            leftHeader = "WHAT YOU SEE",
            rightHeader = "WHAT IT MEANS",
            rows = listOf(
                "White edge panels" to
                    "An adapter is present, or the page is locked. They run the full height of both sides of the screen.",
                "No edge panels" to
                    "No adapter is present and the page is not locked. If they go dark mid session, the adapter itself is gone, either unplugged or the Bluetooth link dropped.",
                "Edge panels flashing red and white" to
                    "The adapter is still there and the channel is still open, but the module stopped answering. It fires after several missed replies in a row, runs about three seconds, and drops you out of lock and tap on its own.",
                "Gauges flash white three times" to
                    "Lock and tap has enabled.",
                "Gauges flash white once" to
                    "A tap registered while locked.",
                "Green checkmark" to
                    "That parameter is on the Live Data page.",
                "Gauge displays two dashes" to
                    "No value yet. Either nothing is being requested, or the module has not answered for that parameter.",
                "Gauge unit reads a stale input" to
                    "That parameter is not in the definition you currently have loaded, so it cannot read anything. Remove it with its \"x.\""
            )
        )
        Para(
            "The difference between the panels going dark and the panels flashing is worth " +
                "knowing. Dark means the adapter is gone. Flashing means the adapter is fine " +
                "and the module went quiet."
        )

        CategoryHeader("THE LOGS")
        Para(
            "The app keeps three logs. Expand any of them and a selector at the top lets you " +
                "move between all three without going back to the page that owns each one."
        )
        RefTable(
            leftHeader = "LOG",
            rightHeader = "WHAT IT HOLDS",
            rows = listOf(
                "MODULE" to "Decoded parameter values from Live Data, one row per poll.",
                "TRANSPORT" to
                    "Every byte on the wire, both directions, with timestamps. Manual Adapter Control and Read / Write ROM both feed this same log, because both are the same bytes on the same link.",
                "CODES" to "Trouble codes from the last DTC read, with their descriptions."
            )
        )
        Para(
            "On the page, a log is a preview. It shows you what is happening and nothing more, " +
                "so a full log can never swallow a swipe or a scroll meant for the page behind " +
                "it. EXPAND opens it full screen, where you can scroll, highlight and copy. " +
                "Back, or CLOSE, returns you to the page."
        )

        CategoryHeader("DTC SCAN")
        Para(
            "Swipe left from Home. READ CODES reports current and stored trouble codes with " +
                "their descriptions. The Clear tab on the card wipes the results off the screen " +
                "and touches nothing in the module."
        )
        Para(
            "RESET is different. It erases the module's stored fault memory, which is a write, " +
                "cannot be undone, and also discards the freeze frame evidence you may still " +
                "need. It asks twice before it fires. Read first, then decide."
        )

        CategoryHeader("RATE, AND WHY IT DROPS")
        Para(
            "Every parameter on the page is another address requested each cycle, and the bus " +
                "runs at a fixed speed. Fewer parameters means a faster refresh, close to " +
                "directly proportional."
        )
        Para(
            "Here is a quick look under the hood. K Line runs at 4800 baud, which once " +
                "framing is counted is roughly 480 bytes a second, or about two milliseconds " +
                "per byte. A request carrying ten single byte addresses is 37 bytes going out, " +
                "and the reply carrying ten values is 16 bytes coming back. That is 53 bytes, " +
                "so roughly 110 milliseconds of transmission before the module has even " +
                "thought about it, depending on your connection, adapter configuration and " +
                "plenty more variables. Push it to fourteen parameters and the same sum comes " +
                "to 69 bytes, which measures out near 6 Hz. That is being at the limit of 4800."
        )
        Para(
            "Some parameters cost more than one address. A four byte value takes four slots " +
                "where a single byte value takes one, so a page of large values costs far more " +
                "than the gauge count suggests. One request also cannot carry more than 84 " +
                "addresses per module. Past that the request cannot be formed, and the read is " +
                "refused rather than sent wrong."
        )
        RefTable(
            leftHeader = "MEASURED",
            rightHeader = "RATE",
            rows = listOf(
                "5 parameters, streamed" to "about 37 Hz",
                "10 parameters, streamed" to "about 25 Hz",
                "14 parameters, polled" to "about 5.8 Hz"
            )
        )
        Para(
            "The link matters as much as the bus. Every round trip pays the link's own latency " +
                "on top of the wire time, and a USB cable's is far below a millisecond while a " +
                "Bluetooth hop is several milliseconds each way and varies with radio " +
                "conditions. In Poll mode you pay that twice per cycle, which is why the same " +
                "parameter list can feel noticeably different over Bluetooth than over USB even " +
                "though the bus speed has not changed."
        )
        Para(
            "Three things are planned to claw that back, and two of them already exist in " +
                "part. Stream mode removes the request half of every round trip, so the link " +
                "latency is paid once instead of once per sample. Batching more addresses into " +
                "a single adapter command cuts the number of round trips outright, which on CAN " +
                "has already measured about an order of magnitude. And decoding the broadcast " +
                "bus directly would mean reading values the car is already publishing, with no " +
                "request at all."
        )

        CategoryHeader("MANUAL ADAPTER CONTROL")
        Para(
            "Home > Manual Adapter Control. This is the page for talking to the adapter and " +
                "the module directly, outside the logging path. This is mostly for RE and " +
                "dialing in sequences."
        )
        Para(
            "The adapter and bus selectors at the top are not a second configuration. They set " +
                "the same values the Configuration page does, and they are repeated here so you can " +
                "swap adapters and reconnect without leaving the page. That matters when you " +
                "are comparing behaviour across hardware. Run a sequence on the OpenPort, " +
                "switch to an OBDLink, run the identical sequence, and compare the transport " +
                "log, all without navigating anywhere. Switching either selector stops any " +
                "running poll and throws away the cached channel, so the next connect always " +
                "runs a clean initiation and you are never quietly talking to the old adapter."
        )
        Para(
            "CONNECT runs the initiation for whatever combination is selected. Selecting alone " +
                "does not connect. STOP halts a running stream or monitor."
        )
        Para(
            "The command box sends one command, formatted for whichever adapter is selected. " +
                "Two kinds go through it. A text command is an adapter control command and is " +
                "sent verbatim, spaces and all. A hex frame is raw bytes, and on K Line " +
                "those bytes go straight onto the wire to the module. That is the important " +
                "one, because you can hand build a real request and watch the module answer. A read of " +
                "coolant is one frame. So is asking the ECM for its identity and capability " +
                "bitmap. Nothing stands between what you type and the module, which is exactly " +
                "why read commands are safe here and anything that writes is not."
        )
        Para(
            "The quick command list underneath is the same thing without the typing. It " +
                "filters to the adapter you have selected, groups entries by what they do, and " +
                "tags each one as text or hex so you can see which are adapter commands and " +
                "which are real frames. Tap one and it drops into the box, so you can fire a " +
                "known good frame first and confirm the link before you start hand editing."
        )
        Para(
            "The sequence generator is for the cases where one command is not the experiment. " +
                "Ten slots run top to bottom with a delay you set between each, and every reply " +
                "lands in that slot's own response line. An initiation is a sequence. So is a " +
                "seed and key exchange, or any handshake where step three only makes sense if " +
                "steps one and two answered correctly. Being able to replay the whole thing, " +
                "change one byte, and replay it again is how an undocumented sequence gets " +
                "worked out."
        )
        Para(
            "The transport log under all of it is the real output of this page. It records " +
                "every byte in both directions with a timestamp, rendered as hex and as " +
                "printable text, and it exports to CSV. That capture is what you diff against a " +
                "known good one to find where a sequence diverged, what a byte you have never " +
                "seen before actually did, and whether the module answered or the adapter " +
                "answered for it. Expand it to read it properly."
        )
        Para(
            "Home > Adapter Command Library is the read only companion. It holds the same " +
                "verified sequences and command sets, with nothing sent. Look there first, send " +
                "here."
        )

        CategoryHeader("READING AND WRITING THE ROM")
        Para(
            "Unless you already have advanced experience with this kind of work, this feature " +
                "should not be used yet. If that advice is not taken, you assume full " +
                "responsibility for what happens to the module and to the vehicle."
        )
        Para(
            "Before anything is written, check all of the following, and check them twice. The " +
                "adapter is fully seated in the diagnostic port and will not be knocked loose. " +
                "The USB connection is solid along its whole length. The phone has plenty of " +
                "battery. Battery saver is OFF, because it can suspend the app partway through " +
                "a write. Do not disturb is ON, so nothing interrupts. The vehicle supply is " +
                "stable and will not drop."
        )
        Para(
            "If you hired a tuner rather than doing this yourself, that responsibility is " +
                "theirs as much as it is yours. This software is documented as unfinished " +
                "reference material, and it stays documented that way until further testing " +
                "proves it reliable across multiple verified sources."
        )
        Para(
            "Home > Read / Write ROM. Both files this page uses are yours. The kernel and the " +
                "ROM are picked with the two buttons at the top, and neither ships with the app."
        )
        RefTable(
            leftHeader = "BUTTON",
            rightHeader = "WHAT IT DOES",
            rows = listOf(
                "READ FIRMWARE" to "Reads the module's ROM out to a file. Safe and reversible, and worth doing on its own.",
                "TEST WRITE" to "Runs the write path without modifying the ROM. It only validates.",
                "COMMIT WRITE" to "The real write. Asks for confirmation first, and is the one operation here that can kill a module."
            )
        )
        Para(
            "A saved original read is the only way back from a bad write. Treat a commit as " +
                "bench work on a spare module, on a supply that will not drop, with a verified " +
                "read of the original already saved. See About & License for the full " +
                "disclaimer."
        )

        CategoryHeader("IF SOMETHING IS NOT WORKING")
        Para(
            "Please tell me. This software is new, and the number of variables is genuinely " +
                "large. Every phone is a slightly different Android, every adapter has its own " +
                "firmware quirks, and a ROM the app has never seen may answer in ways nothing " +
                "here expects. A fault you hit in five minutes could be one nobody has hit yet."
        )
        Para(
            "Reach out on $CONTACT_HANDLE. Send screenshots and a short description of what " +
                "you were doing when it happened. The most useful report names your phone, your " +
                "adapter, the car, and what you had selected at the time."
        )
        Para(
            "An exported log is worth more than a description, however it is recommended that " +
                "you have experience capturing accurate debugging logs. If not, please do not " +
                "send a random debug log to me or to anybody else. They can reveal personal " +
                "information."
        )

        CategoryHeader("TROUBLESHOOTING AND DEBUGGING BASICS")
        Para(
            "If you are willing to go a step further, you can get logs off the phone yourself, " +
                "which turns a vague report into something fixable. This section is optional " +
                "and none of it is required to use the app."
        )
        Para(
            "First, developer mode. On a Samsung, open Android Settings, then About phone, " +
                "then Software information, and tap Build number seven times. On most other " +
                "phones it is Settings, About phone, then tap Build number seven times. You " +
                "will be asked for your PIN. A new Developer options entry then appears in " +
                "Settings, usually near the bottom."
        )
        Para(
            "Inside Developer options you will find USB debugging and Wireless debugging. USB " +
                "debugging works over a cable. Wireless debugging works over your local " +
                "network, which is more convenient but drops when the phone changes networks or " +
                "the connection rotates, so if the tooling suddenly stops seeing the phone, " +
                "that is usually why."
        )
        Para(
            "To pair wirelessly, turn on Wireless debugging, open it, and choose Pair device " +
                "with pairing code. The phone shows an address, a port and a six digit code. On " +
                "a computer with the Android platform tools installed, run adb pair with that " +
                "address and port, enter the code, then run adb connect with the address and " +
                "the port shown on the Wireless debugging screen itself, which is a different " +
                "port from the pairing one."
        )
        Para(
            "Once connected, the tool adb logcat will print what the phone is reporting. Be " +
                "careful with it. It captures everything happening on the device, not only " +
                "this app, and that can include notifications, account names and other personal " +
                "information. Read anything before you share it, and if you are not comfortable " +
                "doing that, do not share it at all."
        )
        Para(
            "You do not need any of this to file a useful report. Screenshots and a short " +
                "description of what you were doing are enough, and they are the safest thing " +
                "to send. Treat logcat as something to learn when you want to, not a step you " +
                "owe anyone."
        )
        Para(
            "The app also has its own developer surface, which is Manual Adapter Control on " +
                "Home. Nothing there is needed for normal logging, but the transport log inside " +
                "it is live at all times and is the fastest way to see whether the adapter is " +
                "talking, whether the module is answering, and which of the two stopped."
        )
    }
}

// ── building blocks ────────────────────────────────────────────────────────

@Composable
private fun Para(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall
    )
}

/** Fixed marker column so wrapped lines stay aligned. */
@Composable
private fun Bullets(vararg points: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (point in points) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "•",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(24.dp)
                )
                Text(
                    text = point,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** Real columns, not space-padded text: the right column wraps in its own width
 *  instead of running off the screen. */
@Composable
private fun RefTable(
    leftHeader: String,
    rightHeader: String,
    rows: List<Pair<String, String>>
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        RefRow(leftHeader, rightHeader, header = true)
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BorderGray)
        )
        for ((left, right) in rows) RefRow(left, right)
    }
}

@Composable
private fun RefRow(left: String, right: String, header: Boolean = false) {
    val weight = if (header) FontWeight.Bold else FontWeight.Normal
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = left,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (header) FontWeight.Bold else FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .weight(0.38f)
                .padding(end = 8.dp)
        )
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(BorderGray)
        )
        Text(
            text = right,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = weight,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .weight(0.62f)
                .padding(start = 8.dp)
        )
    }
}
