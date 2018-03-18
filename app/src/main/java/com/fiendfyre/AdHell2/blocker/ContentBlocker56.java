package com.fiendfyre.AdHell2.blocker;

import android.support.annotation.Nullable;

import com.fiendfyre.AdHell2.App;
import com.fiendfyre.AdHell2.db.AppDatabase;
import com.fiendfyre.AdHell2.db.entity.AppInfo;
import com.fiendfyre.AdHell2.db.entity.UserBlockUrl;
import com.fiendfyre.AdHell2.db.entity.WhiteUrl;
import com.fiendfyre.AdHell2.utils.AdhellAppIntegrity;
import com.fiendfyre.AdHell2.utils.BlockUrlPatternsMatch;
import com.fiendfyre.AdHell2.utils.BlockUrlUtils;
import com.fiendfyre.AdHell2.utils.LogUtils;
import com.sec.enterprise.AppIdentity;
import com.sec.enterprise.firewall.DomainFilterRule;
import com.sec.enterprise.firewall.Firewall;
import com.sec.enterprise.firewall.FirewallResponse;
import com.sec.enterprise.firewall.FirewallRule;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import javax.inject.Inject;

public class ContentBlocker56 implements ContentBlocker {
    private static ContentBlocker56 mInstance = null;
    private final String TAG = ContentBlocker56.class.getCanonicalName();

    @Nullable
    @Inject
    Firewall mFirewall;
    @Inject
    AppDatabase appDatabase;

    private ContentBlocker56() {
        App.get().getAppComponent().inject(this);
    }

    public static ContentBlocker56 getInstance() {
        if (mInstance == null) {
            mInstance = getSync();
        }
        return mInstance;
    }

    private static synchronized ContentBlocker56 getSync() {
        if (mInstance == null) {
            mInstance = new ContentBlocker56();
        }
        return mInstance;
    }

    @Override
    public boolean enableBlocker() {
        if (isEnabled()) {
            disableBlocker();
        }

        processCustomRules();
        boolean result = processMobileRestrictedApps();
        result |= processWhitelistedApps();
        result |= processBlockedDomains();

        if (result) {
            try {
                if (!mFirewall.isFirewallEnabled()) {
                    LogUtils.getInstance().writeInfo("Enabling firewall...");
                    mFirewall.enableFirewall(true);
                }
                if (!mFirewall.isDomainFilterReportEnabled()) {
                    LogUtils.getInstance().writeInfo("Enabling firewall report...");
                    mFirewall.enableDomainFilterReport(true);
                }
            } catch (SecurityException e) {
                LogUtils.getInstance().writeError("Failed to enable firewall: " + e.getMessage(), e);
            }
        }

        LogUtils.getInstance().writeInfo("Done");
        LogUtils.getInstance().close();

        return result;
    }

    private void processCustomRules() {
        LogUtils.getInstance().writeInfo("\nProcessing custom rules...");
        List<UserBlockUrl> userBlockUrls = appDatabase.userBlockUrlDao().getAll2();
        for (UserBlockUrl userBlockUrl : userBlockUrls) {
            if (userBlockUrl.url.indexOf('|') != -1) {
                StringTokenizer tokens = new StringTokenizer(userBlockUrl.url, "|");
                if (tokens.countTokens() == 3) {
                    String packageName = tokens.nextToken();
                    String ipAddress = tokens.nextToken();
                    String port = tokens.nextToken();

                    // Define firewall rule
                    FirewallRule[] firewallRules = new FirewallRule[1];
                    firewallRules[0] = new FirewallRule(FirewallRule.RuleType.DENY, Firewall.AddressType.IPV4);
                    firewallRules[0].setIpAddress(ipAddress);
                    firewallRules[0].setPortNumber(port);
                    firewallRules[0].setApplication(new AppIdentity(packageName, null));

                    // Send rules to the firewall
                    FirewallResponse[] response = null;
                    try {
                        LogUtils.getInstance().writeInfo("Adding firewall rule '" + userBlockUrl.url + "' to Knox Firewall...");
                        response = mFirewall.addRules(firewallRules);
                        LogUtils.getInstance().writeInfo("Result: " + response[0].getMessage());
                    } catch (SecurityException ex) {
                        // Missing required MDM permission
                        LogUtils.getInstance().writeError("Failed to add rule '" + userBlockUrl.url + "' to Knox Firewall", ex);
                    }
                }
            }
        }
    }

    private boolean processMobileRestrictedApps() {
        LogUtils.getInstance().writeInfo("\nProcessing mobile restricted apps...");

        List<AppInfo> restrictedApps = appDatabase.applicationInfoDao().getMobileRestrictedApps();
        LogUtils.getInstance().writeInfo("Restricted apps size: " + restrictedApps.size());
        if (restrictedApps.size() == 0) {
            return true;
        }

        // Define DENY rules for mobile data
        FirewallRule[] mobileRules = new FirewallRule[restrictedApps.size()];
        for (int i = 0; i < restrictedApps.size(); i++) {
            mobileRules[i] = new FirewallRule(FirewallRule.RuleType.DENY, Firewall.AddressType.IPV4);
            mobileRules[i].setNetworkInterface(Firewall.NetworkInterface.MOBILE_DATA_ONLY);
            mobileRules[i].setApplication(new AppIdentity(restrictedApps.get(i).packageName, null));
        }

        // Send rules to the firewall
        FirewallResponse[] response = null;
        try {
            LogUtils.getInstance().writeInfo("Adding firewall rule to Knox Firewall...");
            response = mFirewall.addRules(mobileRules);
            LogUtils.getInstance().writeInfo("Result: " + response[0].getMessage());
        } catch (SecurityException ex) {
            // Missing required MDM permission
            LogUtils.getInstance().writeError("Failed to add firewall rules to Knox Firewall", ex);
        }
        return response != null && (FirewallResponse.Result.SUCCESS == response[0].getResult());
    }

    private boolean processWhitelistedApps() {
        LogUtils.getInstance().writeInfo("\nProcessing white-listed apps...");

        // Create domain filter rule for white listed apps
        List<AppInfo> whitelistedApps = appDatabase.applicationInfoDao().getWhitelistedApps();
        LogUtils.getInstance().writeInfo("Whitelisted apps size: " + whitelistedApps.size());
        if (whitelistedApps.size() == 0) {
            return true;
        }

        List<DomainFilterRule> rules = new ArrayList<>();
        List<String> superAllow = new ArrayList<>();
        superAllow.add("*");
        for (AppInfo app : whitelistedApps) {
            LogUtils.getInstance().writeInfo(app.packageName);
            rules.add(new DomainFilterRule(new AppIdentity(app.packageName, null), new ArrayList<>(), superAllow));
        }

        // Add domain filter rule to Knox Firewall
        LogUtils.getInstance().writeInfo("Adding domain filter rule to Knox Firewall...");
        FirewallResponse[] response = null;
        try {
            response = mFirewall.addDomainFilterRules(rules);
            LogUtils.getInstance().writeInfo("Result: " + response[0].getMessage());
        } catch (SecurityException ex) {
            // Missing required MDM permission
            LogUtils.getInstance().writeError("Failed to add domain filter rule to Knox Firewall", ex);
        }
        return response != null && (FirewallResponse.Result.SUCCESS == response[0].getResult());
    }

    private boolean processBlockedDomains() {
        LogUtils.getInstance().writeInfo("\nProcessing blocked domains...");

        // Process user-defined white list
        List<WhiteUrl> whiteUrls = appDatabase.whiteUrlDao().getAll2();
        Set<String> whiteList = new HashSet<>();
        for (WhiteUrl whiteUrl : whiteUrls) {
            final String url = BlockUrlPatternsMatch.getValidatedUrl(whiteUrl.url);
            whiteList.add(url);
            LogUtils.getInstance().writeInfo("WhiteUrl: " + url);
        }
        LogUtils.getInstance().writeInfo("White list size: " + whiteList.size());

        // Process blocked URLs
        Set<String> denyList = BlockUrlUtils.getUniqueBlockedUrls(appDatabase, true);

        // Create domain filter rule with deny and white list
        List<DomainFilterRule> rules = new ArrayList<>();
        AppIdentity appIdentity = new AppIdentity("*", null);
        rules.add(new DomainFilterRule(appIdentity, new ArrayList<>(denyList), new ArrayList<>(whiteList)));

        // Add domain filter rule to Knox Firewall
        LogUtils.getInstance().writeInfo("Adding domain filter rule to Knox Firewall...");
        FirewallResponse[] response = null;
        try {
            response = mFirewall.addDomainFilterRules(rules);
            LogUtils.getInstance().writeInfo("Result: " + response[0].getMessage());
        } catch (SecurityException ex) {
            // Missing required MDM permission
            LogUtils.getInstance().writeError("Failed to add domain filter rule to Knox Firewall", ex);
        }
        return response != null && (FirewallResponse.Result.SUCCESS == response[0].getResult());
    }

    @Override
    public boolean disableBlocker() {
        FirewallResponse[] response;
        try {
            // Clear IP rules
            response = mFirewall.clearRules(Firewall.FIREWALL_ALL_RULES);

            // Clear domain filter rules
            response = mFirewall.removeDomainFilterRules(DomainFilterRule.CLEAR_ALL);

            LogUtils.getInstance().writeInfo(response[0].getMessage());
            if (mFirewall.isFirewallEnabled()) {
                mFirewall.enableFirewall(false);
            }
            if (mFirewall.isDomainFilterReportEnabled()) {
                mFirewall.enableDomainFilterReport(false);
            }
        } catch (SecurityException ex) {
            LogUtils.getInstance().writeError("Failed to remove firewall rules", ex);
            return false;
        }
        return true;
    }

    @Override
    public boolean isEnabled() {
        return mFirewall.isFirewallEnabled();
    }

}