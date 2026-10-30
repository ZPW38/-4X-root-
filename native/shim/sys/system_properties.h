/* Minimal stand-in for bionic's <sys/system_properties.h>.
 *
 * The payload is cross-compiled against musl with zig because this machine has
 * no NDK. Only one bionic symbol is actually used — `__system_property_get`,
 * in memory/address_space.cpp::detect_target_soc — and the payload only ever
 * runs on the Huawei NIK-AL00 (Kirin 990), which is none of the SoC families
 * that function can recognise (Google / MediaTek / Xring).
 *
 * Returning 0 ("property not found", the same contract bionic uses) therefore
 * makes detect_target_soc fall through to its Qcom default, which is the exact
 * value the platform-independent branch of that function returns and the one
 * the 4.14.116 profile was built against.
 *
 * If a future target needs the real property area, switch this build to the
 * NDK instead of extending this shim.
 */
#ifndef WATCHROOT_SHIM_SYSTEM_PROPERTIES_H
#define WATCHROOT_SHIM_SYSTEM_PROPERTIES_H

#ifdef __cplusplus
extern "C" {
#endif

/* bionic: returns the value length, or 0 when the property does not exist. */
static inline int __system_property_get(const char *name, char *value) {
    (void) name;
    if (value) {
        value[0] = '\0';
    }
    return 0;
}

#ifdef __cplusplus
}
#endif

#endif /* WATCHROOT_SHIM_SYSTEM_PROPERTIES_H */
