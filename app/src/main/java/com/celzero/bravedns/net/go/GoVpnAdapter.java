/*
Copyright 2020 RethinkDNS developers

Copyright 2019 Jigsaw Operations LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.net.go;

import android.content.Context;
import android.content.res.Resources;
import android.net.VpnService;
import android.os.AsyncTask;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.celzero.bravedns.R;
import com.celzero.bravedns.data.AppMode;
import com.celzero.bravedns.database.DNSProxyEndpoint;
import com.celzero.bravedns.database.ProxyEndpoint;
import com.celzero.bravedns.service.BraveVPNService;
import com.celzero.bravedns.service.PersistentState;
import com.celzero.bravedns.ui.HomeScreenActivity;
import com.celzero.bravedns.util.Constants;
import com.celzero.bravedns.util.Utilities;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dnsx.BraveDNS;
import dnsx.Dnsx;
import doh.Transport;
import intra.Tunnel;
import protect.Blocker;
import protect.Protector;
import settings.Settings;
import tun2socks.Tun2socks;

import static com.celzero.bravedns.BuildConfig.DEBUG;
import static com.celzero.bravedns.util.Constants.UNSPECIFIED_IP;
import static com.celzero.bravedns.util.Constants.UNSPECIFIED_PORT;
import static com.celzero.bravedns.util.LoggerConstants.LOG_TAG_APP_MODE;
import static com.celzero.bravedns.util.LoggerConstants.LOG_TAG_VPN;


/**
 * This is a VpnAdapter that captures all traffic and routes it through a go-tun2socks instance with
 * custom logic for Intra.
 */
public class GoVpnAdapter {
    // This value must match the hardcoded MTU in outline-go-tun2socks.
    // TODO: Make outline-go-tun2socks's MTU configurable.
    private static final int VPN_INTERFACE_MTU = 1500;
    private static final int DNS_DEFAULT_PORT = 53;

    // IPv4 VPN constants
    private static final String IPV4_TEMPLATE = "10.111.222.%d";
    private static final int IPV4_PREFIX_LENGTH = 24;

    // The VPN service and tun2socks must agree on the layout of the network.  By convention, we
    // assign the following values to the final byte of an address within a subnet.
    private enum LanIp {
        GATEWAY(1), ROUTER(2), DNS(3);

        // Value of the final byte, to be substituted into the template.
        private final int value;

        LanIp(int value) {
            this.value = value;
        }

        String make(String template) {
            return String.format(Locale.ROOT, template, value);
        }
    }

    public static final String FAKE_DNS_IP = LanIp.DNS.make(IPV4_TEMPLATE);

    // Service context in which the VPN is running.
    private final BraveVPNService vpnService;

    // TUN device representing the VPN.
    private ParcelFileDescriptor tunFd;

    // The Intra session object from go-tun2socks.  Initially null.
    private Tunnel tunnel;

    private GoIntraListener listener;

    private final AppMode appMode;

    @NonNull
    private final PersistentState persistentState;

    private BraveDNS localBraveDns;

    public static GoVpnAdapter establish(@NonNull BraveVPNService vpnService,
                                         @NonNull AppMode appMode,
                                         @NonNull PersistentState persistentState) {
        ParcelFileDescriptor tunFd = establishVpn(vpnService, appMode);

        if (tunFd == null) {
            return null;
        }

        return new GoVpnAdapter(vpnService, tunFd, appMode, persistentState);
    }

    private GoVpnAdapter(BraveVPNService vpnService, ParcelFileDescriptor tunFd,
                         AppMode appMode,
                         @NotNull PersistentState persistentState) {
        this.vpnService = vpnService;
        this.tunFd = tunFd;
        this.appMode = appMode;
        this.persistentState = persistentState;
    }

    public synchronized void start(AppMode.TunnelMode tunnelMode) {
        connectTunnel(tunnelMode);
    }

    private void connectTunnel(AppMode.TunnelMode tunnelMode) {
        if (tunnel != null) {
            return;
        }
        // VPN parameters
        final String fakeDns = FAKE_DNS_IP + ":" + DNS_DEFAULT_PORT;

        // Strip leading "/" from ip:port string.
        listener = new GoIntraListener(vpnService);
        //TODO : The below statement is incorrect, adding the dohURL as const for testing

        try {
            // TODO : #321 As of now the app fallback on an unmaintained url. Requires a rewrite as
            // part of v055
            String dohURL = getDoHUrl();

            Transport transport = makeDohTransport(dohURL);

            Log.i(LOG_TAG_VPN, "Connect tunnel with url " + dohURL + ", dnsMode: " + tunnelMode.getDnsMode() + ", blockMode: " + tunnelMode.getFirewallMode() + ", proxyMode: " + tunnelMode.getProxyMode());
            tunnel = Tun2socks.connectIntraTunnel(tunFd.getFd(), fakeDns,
                    transport, getProtector(), getBlocker(), listener);

            if (HomeScreenActivity.GlobalVariable.INSTANCE.getDEBUG()) {
                Tun2socks.enableDebugLog();
            }

            setBraveMode(tunnelMode.getDnsMode(), dohURL);

            setTunnelMode(tunnelMode);

        } catch (Exception e) {
            Log.e(LOG_TAG_VPN, e.getMessage(), e);
            if (tunnel != null) tunnel.disconnect();
            tunnel = null;
        }
    }

    private boolean isRethinkUrl(String url) {
        return url.contains(Constants.BRAVE_BASIC_URL) || url.contains(Constants.RETHINK_BASIC_URL);
    }

    private boolean isDnscrypt(long dnsMode) {
        return (Settings.DNSModeCryptIP == dnsMode || Settings.DNSModeCryptPort == dnsMode);
    }

    private boolean isDnsProxy(long dnsMode) {
        return Settings.DNSModeProxyIP == dnsMode || Settings.DNSModeProxyPort == dnsMode;
    }

    private boolean isSock5Proxy(long proxyMode) {
        return Settings.ProxyModeSOCKS5 == proxyMode;
    }

    private boolean isOrbotProxy(long proxyMode) {
        return Constants.ORBOT_PROXY == proxyMode;
    }

    private boolean isDoh(long dnsMode) {
        return (Settings.DNSModeIP == dnsMode || Settings.DNSModePort == dnsMode);
    }

    private void setTunnelMode(AppMode.TunnelMode tunnelMode) {
        if (!isDnscrypt(tunnelMode.getDnsMode())) {
            if (isOrbotProxy(tunnelMode.getProxyMode())) {
                tunnel.setTunMode(tunnelMode.getDnsMode(), tunnelMode.getFirewallMode(), Settings.ProxyModeSOCKS5);
            } else {
                tunnel.setTunMode(tunnelMode.getDnsMode(), tunnelMode.getFirewallMode(), tunnelMode.getProxyMode());
            }
            checkForCryptRemoval();
            if (isDnsProxy(tunnelMode.getDnsMode())) {
                setDNSProxy();
            }

            if (isSock5Proxy(tunnelMode.getProxyMode()) || isOrbotProxy(tunnelMode.getProxyMode())) {
                setSocks5TunnelMode(tunnelMode.getProxyMode());
            }
        } else {
            setDnscryptMode(tunnelMode);
        }
    }

    private void setBraveMode(long dnsMode, String dohURL) {
        if (DEBUG) Log.d(LOG_TAG_VPN, "Set brave dns mode initiated");
        // Set brave mode only if the selected DNS is either DoH or DnsCrypt
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (isDoh(dnsMode) || isDnscrypt(dnsMode)) {
                    setBraveDNSLocalMode();
                    setBraveDNSRemoteMode(dohURL);
                }
            }
        });
    }

    private void setBraveDNSRemoteMode(String dohURL) {
        // Brave mode remote will be set only if the selected DoH is RethinkDns
        // and if the local brave dns is not set in the tunnel.
        if (!isRethinkUrl(dohURL) || localBraveDns != null) {
            return;
        }

        try {
            String path = getRemoteBlocklistFilePath();
            if (path == null) return;

            File remoteFile = new File(path);

            if (remoteFile.exists()) {
                BraveDNS rbdns = Dnsx.newBraveDNSRemote(path);
                tunnel.setBraveDNS(rbdns);
                Log.i(LOG_TAG_VPN, "Enabled remote bravedns mode");
            } else {
                Log.w(LOG_TAG_VPN, "Remote blocklist filetag.json does not exists");
            }
        } catch (Exception ex) {
            Log.e(LOG_TAG_VPN, "Cannot set remote bravedns:" + ex.getMessage(), ex);
        }
    }

    private String getRemoteBlocklistFilePath() {
        try {
            return vpnService.getFilesDir().getCanonicalPath() + File.separator + persistentState.getRemoteBlocklistDownloadTime() + File.separator + Constants.FILE_TAG_JSON;
        } catch (IOException e) {
            Log.e(LOG_TAG_VPN, "Could not fetch remote blocklist: " + e.getMessage(), e);
            return null;
        }
    }

    public void checkForCryptRemoval() {
        try {
            if (tunnel.getDNSCryptProxy() != null) {
                tunnel.stopDNSCryptProxy();
                Log.i(LOG_TAG_VPN, "connect-tunnel - stopDNSCryptProxy");
            }
        } catch (Exception e) {
            Log.e(LOG_TAG_VPN, "stop dnscrypt failure: " + e.getMessage(), e);
        }
    }

    public void setDnscryptMode(AppMode.TunnelMode tunnelMode) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                setDnscrypt(tunnelMode);
            }
        });
    }

    private void setDnscrypt(AppMode.TunnelMode tunnelMode) {
        String servers = appMode.getDnscryptServers();
        String routes = appMode.getDnscryptRelayServers();
        String serversIndex = appMode.getDnscryptServersToRemove();
        try {
            if (tunnel.getDNSCryptProxy() == null) {
                String response;
                response = tunnel.startDNSCryptProxy(servers, routes, listener);
                Log.i(LOG_TAG_VPN, "startDNSCryptProxy: " + servers + "," + routes + ", Response: " + response);
            } else {
                long serverCount = 0L, relayCount = 0L;
                String serversToRemove = Utilities.Companion.prepareServersToRemove(tunnel.getDNSCryptProxy().liveServers(), serversIndex);
                if (!serversToRemove.isEmpty())
                    serverCount = tunnel.getDNSCryptProxy().removeServers(serversToRemove);
                if (!AppMode.Companion.getCryptRelayToRemove().isEmpty()) {
                    tunnel.getDNSCryptProxy().removeRoutes(AppMode.Companion.getCryptRelayToRemove());
                    AppMode.Companion.setCryptRelayToRemove("");
                }
                if (!routes.isEmpty())
                    relayCount = tunnel.getDNSCryptProxy().removeRoutes(routes);
                tunnel.getDNSCryptProxy().addServers(servers);
                if (!routes.isEmpty())
                    tunnel.getDNSCryptProxy().addRoutes(routes);

                Log.i(LOG_TAG_VPN, "DNSCrypt - Routes: " + routes + ", relay count: " + relayCount + ", servers: " + servers + ", removed count:" + serverCount);
            }
        } catch (Exception ex) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: dns crypt failure", ex);

        }
        if (servers.length() > 0) {
            RefreshOperation runningTask = new RefreshOperation(tunnelMode);
            runningTask.execute();
        }
    }

    private void setDNSProxy() {
        try {
            DNSProxyEndpoint dnsProxy = appMode.getConnectedProxyDetails();
            if (HomeScreenActivity.GlobalVariable.INSTANCE.getDEBUG())
                Log.d(LOG_TAG_VPN, "setDNSProxy mode set: " + dnsProxy.getProxyIP() + ", " + dnsProxy.getProxyPort());
            tunnel.startDNSProxy(dnsProxy.getProxyIP(), Integer.toString(dnsProxy.getProxyPort()));
        } catch (Exception e) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: dns proxy" + e.getMessage(), e);
        }
    }

    /**
     * TODO - Move these code to common place and set the tunnel mode and
     * other parameters. Return the tunnel to the adapter.
     */
    private void setProxyMode(String userName, String password, String ipAddress, int port) {
        try {
            tunnel.startProxy(userName, password, ipAddress, Integer.toString(port));
            Log.i(LOG_TAG_VPN, "Proxy mode set: " + userName + ipAddress + port);
        } catch (Exception e) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: proxy", e);
        }
    }

    // TODO: Change it to ExecutorService
    private final class RefreshOperation extends AsyncTask<Void, Void, String> {
        long proxyMode;
        long firewallMode;
        public RefreshOperation(AppMode.TunnelMode tunnelMode) {
            this.proxyMode = tunnelMode.getProxyMode();
            this.firewallMode = tunnelMode.getFirewallMode();
        }

        //TODO - #321
        String asyncComplete = "Executed";

        @Override
        protected String doInBackground(Void... params) {
            try {
                if (tunnel.getDNSCryptProxy() != null) {
                    String liveServers = tunnel.getDNSCryptProxy().refresh();
                    appMode.updateDnscryptLiveServers(liveServers);

                    Log.i(LOG_TAG_VPN, "Refresh LiveServers: " + liveServers);

                    if (liveServers.isEmpty()) {
                        Log.i(LOG_TAG_VPN, "No live servers, falling back to default DoH mode");
                        tunnel.stopDNSCryptProxy();
                        appMode.setDefaultConnection();
                        showDnscryptConnectionFailureToast();
                    } else {
                        tunnel.setTunMode(Settings.DNSModeCryptPort, firewallMode, proxyMode);
                        if (proxyMode == Settings.ProxyModeSOCKS5 || proxyMode == Constants.ORBOT_PROXY) {
                            setSocks5TunnelMode(proxyMode);
                        }
                        Log.i(LOG_TAG_VPN, "connect crypt tunnel set with mode: " + Settings.DNSModeCryptPort + firewallMode + proxyMode);
                    }
                } else {
                    handleDnscryptFailure();
                }
            } catch (Exception e) {
                handleDnscryptFailure();
                Log.e(LOG_TAG_VPN, "connect-tunnel: dns crypt", e);
            }
            return asyncComplete;
        }

        private void handleDnscryptFailure() {
            if (persistentState.getDnsType() == Constants.PREF_DNS_MODE_DNSCRYPT) {
                appMode.setDefaultConnection();
                showDnscryptConnectionFailureToast();
                Log.i(LOG_TAG_VPN, "connect-tunnel: failure of dns crypt falling back to doh");
            }
        }

        private void showDnscryptConnectionFailureToast() {
            Handler handler = new Handler(vpnService.getMainLooper());
            handler.post(new Runnable() {
                public void run() {
                    String message = vpnService.getString(R.string.dns_crypt_connection_failure);
                    Utilities.Companion.showToastUiCentered(vpnService, message, Toast.LENGTH_SHORT);
                }
            });
        }

        @Override
        protected void onPostExecute(String result) {

        }
    }

    public void setSocks5TunnelMode(long proxyMode) {
        ProxyEndpoint socks5;
        if (proxyMode == Constants.ORBOT_PROXY) {
            socks5 = appMode.getOrbotProxyDetails();
        } else {
            socks5 = appMode.getSocks5ProxyDetails();
        }
        if (socks5 == null) {
            Log.w(LOG_TAG_VPN, "could not fetch socks5 details for proxyMode: " + proxyMode);
            return;
        }

        setProxyMode(socks5.getUserName(), socks5.getPassword(), socks5.getProxyIP(), socks5.getProxyPort());
        Log.i(LOG_TAG_VPN, "Socks5 mode set: " + socks5.getProxyIP() + "," + socks5.getProxyPort());
    }

    private static ParcelFileDescriptor establishVpn(BraveVPNService vpnService, AppMode appMode) {
        try {
            VpnService.Builder builder = vpnService.newBuilder()
                    .setSession("RethinkDNS")
                    .setMtu(VPN_INTERFACE_MTU)
                    .addAddress(LanIp.GATEWAY.make(IPV4_TEMPLATE), IPV4_PREFIX_LENGTH);
            if (appMode.isDnsMode()) {
                builder.addRoute(LanIp.DNS.make(IPV4_TEMPLATE), 32);
                builder.addDnsServer(LanIp.DNS.make(IPV4_TEMPLATE));
            } else if (appMode.isFirewallMode()) {
                builder.addRoute(UNSPECIFIED_IP, UNSPECIFIED_PORT);
            } else {
                builder.addDnsServer(LanIp.DNS.make(IPV4_TEMPLATE));
                builder.addRoute(UNSPECIFIED_IP, UNSPECIFIED_PORT);
            }

            return builder.establish();
        } catch (Exception e) {
            Log.e(LOG_TAG_VPN, e.getMessage(), e);
            return null;
        }
    }

    public boolean hasTunnel() {
        return (tunnel != null);
    }

    private @Nullable
    Protector getProtector() {
        if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
            // We don't need socket protection in these versions because the call to
            // "addDisallowedApplication" effectively protects all sockets in this app.
            return null;
        }
        return vpnService;
    }

    Blocker getBlocker() {
        return vpnService;
    }

    public synchronized void close() {
        if (tunnel != null) {
            tunnel.disconnect();
            Log.i(LOG_TAG_VPN, "Tunnel disconnect");
        }
        if (tunFd != null) {
            try {
                tunFd.close();
            } catch (IOException e) {
                Log.e(LOG_TAG_VPN, e.getMessage(), e);
            }
        }
        tunFd = null;
        tunnel = null;
    }

    private doh.Transport makeDohTransport(@Nullable String url) throws Exception {
        //PersistantState persistentState  = new PersistantState();
        //VpnController vpnController = new VpnController();
        //TODO : Check the below code
        //@NonNull String realUrl = PersistentState.Companion.expandUrl(vpnService, url);
        String dohIPs = getIpString(vpnService, url);
        return Tun2socks.newDoHTransport(url, dohIPs, getProtector(), null, listener);
    }

    /**
     * Updates the DOH server URL for the VPN.  If Go-DoH is enabled, DNS queries will be handled in
     * Go, and will not use the Java DoH implementation.  If Go-DoH is not enabled, this method
     * has no effect.
     */
    public synchronized void updateDohUrl(AppMode.TunnelMode tunnelMode) {
        // FIXME: 18-10-2020  - Check for the tunFD null code. Removed because of the connect tunnel

        // changes made in connectTunnel()
        if (tunFd == null) {
            // Adapter is closed.
            return;
        }

        // Introduced new variable isAdapterAvailable instead of tunFd check.
        /*if(!isAdapterAvailable){
            return;
        }*/
        if (tunnel == null) {
            // Attempt to re-create the tunnel.  Creation may have failed originally because the DoH
            // server could not be reached.  This will update the DoH URL as well.
            connectTunnel(tunnelMode);
            return;
        }

        // Overwrite the DoH Transport with a new one, even if the URL has not changed.  This function
        // is called on network changes, and it's important to switch to a fresh transport because the
        // old transport may be using sockets on a deleted interface, which may block until they time
        // out.
        String dohURL = getDoHUrl();
        try {
            //For invalid URL connection request.
            //Check makeDohTransport, if it is not resolved don't close the tunnel.
            //So handling the exception in makeDohTransport and not resetting the tunnel. Below is the exception thrown from Tun2socks.aar
            //I/GoLog: Failed to read packet from TUN: read : bad file descriptor
            doh.Transport dohTransport = makeDohTransport(dohURL);

            tunnel.setDNS(dohTransport);

            Log.i(LOG_TAG_VPN, "Connect tunnel with url " + dohURL + ", dnsMode: " + tunnelMode.getDnsMode() + ", blockMode:" + tunnelMode.getFirewallMode() + ", proxyMode:" + tunnelMode.getProxyMode());

            // Set brave dns to tunnel - Local/Remote
            setBraveMode(tunnelMode.getDnsMode(), dohURL);

            setTunnelMode(tunnelMode);
        } catch (Exception e) {
            Log.e(LOG_TAG_VPN, e.getMessage(), e);
            tunnel.disconnect();
            tunnel = null;
        }
    }

    private String getDoHUrl() {
        String dohURL = "https://basic.bravedns.com/dns-query";
        try {
            if (appMode.getDOHDetails() != null) {
                dohURL = appMode.getDOHDetails().getDohURL();
            }
        } catch (Exception e) {
            Log.e(LOG_TAG_VPN, "dohURL is null", e);
        }
        return dohURL;
    }

    private void setBraveDNSLocalMode() {
        try {
            if (!persistentState.getBlocklistFilesDownloaded() || !persistentState.getBlocklistEnabled()) {
                Log.i(LOG_TAG_VPN, "local stamp is set to null(on GO)");
                tunnel.setBraveDNS(null);
                localBraveDns = null;
                return;
            }

            // Set the localBraveDns object
            setupLocalBraveDns();

            String stamp = persistentState.getLocalBlocklistStamp();
            Log.i(LOG_TAG_VPN, "app dns mode is set with local stamp: " + stamp);

            if (stamp.isEmpty() || localBraveDns == null) {
                return;
            }

            // Set bravedns object to tunnel if the stamp and localBraveDns object is available.
            tunnel.setBraveDNS(localBraveDns);
            tunnel.getBraveDNS().setStamp(stamp);
        } catch (Exception ex) {
            Log.e(LOG_TAG_VPN, "Exception while setting brave dns for local:" + ex.getMessage(), ex);
        }
    }

    private void setupLocalBraveDns() {
        if (localBraveDns != null) {
            Log.i(LOG_TAG_VPN, "Local brave dns object already available");
            return;
        }

        try {
            String path = vpnService.getFilesDir().getCanonicalPath() + File.separator + persistentState.getBlocklistDownloadTime();
            localBraveDns = Dnsx.newBraveDNSLocal(path + Constants.Companion.getFILE_TD_FILE(),
                    path + Constants.Companion.getFILE_RD_FILE(),
                    path + Constants.Companion.getFILE_BASIC_CONFIG(),
                    path + Constants.Companion.getFILE_TAG_NAME());
        } catch (Exception e) {
            Log.e(LOG_TAG_APP_MODE, "Local brave dns set exception :${e.message}", e);
            // Set local blocklist enabled to false if there is a failure creating bravedns
            // from GO.
            persistentState.setBlocklistEnabled(false);
        }

    }

    static String getIpString(Context context, String url) {
        Resources res = context.getResources();
        String[] urls = res.getStringArray(R.array.urls);
        String[] ips = res.getStringArray(R.array.ips);
        for (int i = 0; i < urls.length; ++i) {
            // TODO: Consider relaxing this equality condition to a match on just the domain.
            //Code has been modified from equals to contains to match on the domain. Need to come
            //up with some other solution to check by extracting the domain name
            if (urls[i].contains(url)) {
                return ips[i];
            }
        }
        return "";
    }
}
