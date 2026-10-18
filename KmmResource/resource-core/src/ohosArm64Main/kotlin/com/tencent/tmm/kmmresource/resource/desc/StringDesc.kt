/*
 * Copyright 2019 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */

package com.tencent.tmm.kmmresource.resource.desc


actual interface StringDesc {
    actual sealed class LocaleType {
        actual object System : LocaleType()

        // Unlike the previous stub (which dropped the value), the tag is kept
        // readable so hosts can route resource lookups through a locale
        // override (e.g. OHOS override ResourceManager). Parity with
        // androidMain/appleMain.
        actual class Custom actual constructor(val locale: String) : LocaleType()
    }

    actual companion object {
        // Real storage (was a hardcoded System getter / no-op setter). The
        // Kotlin/Native new memory model allows plain shared mutable state
        // here, same as androidMain.
        actual var localeType: LocaleType = LocaleType.System
    }

}
