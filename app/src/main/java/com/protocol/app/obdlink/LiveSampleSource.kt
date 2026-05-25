package com.protocol.app.obdlink

import com.protocol.app.openport2.PollSample
import com.protocol.app.openport2.Ssm2Pid
import kotlinx.coroutines.flow.Flow

/**
 * Transport-agnostic source of live poll samples for the Live Data page.
 *
 * Lets the ViewModel drive either the existing USB (OpenPort/Ssm2Poller) path
 * or the OBDLink Bluetooth path through one seam — both ultimately emit the
 * same [PollSample] the gauges already consume. The USB path is wrapped into
 * this shape when the VM wiring lands; [ObdLinkLiveSource] is the Bluetooth
 * implementation.
 */
interface LiveSampleSource {
    /** Open / initialize the channel to the ECU. Returns true on success. */
    fun initChannel(): Boolean

    /** Emit one decoded [PollSample] per cycle until the collector is cancelled. */
    fun startFlow(intervalMs: Long): Flow<PollSample>

    /** Swap the polled PID set without tearing down the flow. */
    fun updatePids(pids: List<Ssm2Pid>)

    /** Release any resources held by the source. */
    fun close()
}
