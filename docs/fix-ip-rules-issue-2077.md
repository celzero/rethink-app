# Fix for Issue #2077: IP Address Rules Ignored by Firewall

## Problem Description
After updating to v0.5.5o, the firewall was ignoring allow list rules for IP address ranges. This specifically affected apps like Telegram that only use IP addresses for connections (instead of domain names).

### Example Scenario
- App: Telegram (uses IP addresses in range 149.154.167.0/22)
- Configuration: Isolated mode with allow connections to specific IP addresses
- Result: Connections to allowed IP addresses were still being blocked

## Root Cause
The bug was located in `BraveVPNService.kt` in the `ipStatus` function at line 867.

When IPv4-in-IPv6 filtering was enabled and a rule was found for the IPv4 address, the function was incorrectly returning `statusIpPort` instead of `statusIpPort4in6`.

### Code Flow
1. Check if IP rule exists for the destination IP → `statusIpPort`
2. If IPv4-in-IPv6 filtering is enabled:
   - Extract IPv4 address from IPv6
   - Check if rule exists for IPv4 address → `statusIpPort4in6`
   - **BUG**: Return `statusIpPort` instead of `statusIpPort4in6`

### Impact
- Allow/Trust rules for IP addresses were ignored
- Apps using only IP addresses (like Telegram) couldn't connect even with explicit allow rules
- Affected IPv4-in-IPv6 filtering scenarios

## Solution
**File**: `app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt`
**Line**: 867
**Change**: `return statusIpPort` → `return statusIpPort4in6`

```kotlin
// Before (buggy)
if (statusIpPort4in6 != IpRulesManager.IpRuleStatus.NONE) {
    return statusIpPort // ← Wrong variable!
}

// After (fixed)
if (statusIpPort4in6 != IpRulesManager.IpRuleStatus.NONE) {
    return statusIpPort4in6 // ← Correct variable
}
```

## Testing
Added unit tests in `app/src/test/java/com/celzero/bravedns/service/IpRulesTest.kt` to:
- Validate IpRuleStatus enum values and behavior
- Document expected behavior for IP rule return values
- Ensure the fix works as intended

## Verification
To verify the fix works:
1. Set up an app in isolated mode (e.g., Telegram)
2. Add allow rules for specific IP address ranges (e.g., 149.154.167.0/22)
3. Attempt connections to those IP addresses
4. Connections should now be allowed instead of blocked

## Related Issues
- Issue #2077: IP address rules are ignored by firewall (this fix)
- Issue #1297: Trust domains does not work (related issue that was supposedly fixed in v055e)

The fix ensures that IP address firewall rules work correctly for both domain-based and IP-based connections.