package nl.w8mr.jafun

import kotlin.test.Test

class PackagesTest {
    @Test
    fun `enumerate packages`() {
        Package.getPackages()
            .map(Package::getName).sorted().forEach { println(it) }
    }
}
