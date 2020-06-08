package com.celzero.bravedns.service

class VpnState(requested: Boolean, on: Boolean, connectionState: BraveVPNService.State?) {

    var activationRequested = false

    // Whether the VPN is running.  When this is true a key icon is showing in the status bar.
    var on = false

    // Whether we have a connection to a DOH server, and if so, whether the connection is ready or
    // has recently been failing.
    var connectionState: BraveVPNService.State? = null

    init {
        this.activationRequested = requested
        this.on = on
        this.connectionState = connectionState
    }

}
