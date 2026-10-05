package com.wax.module.xposed.utils

class HKDFv3 : HKDF() {
    override val iterationStartOffset: Int
        get() = 1
}
