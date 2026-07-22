package com.github.dkwasniak.goldendiff.naming

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortGoldenNameTest {

    @Test
    fun `strips fully-qualified package and class prefix`() {
        assertEquals(
            "VehicleSelectionScreenPreview.Dark_en_NIGHT_PIXEL_7_PRO0.png",
            shortGoldenName(
                "com.cleevio.ionity.android.feature.routeplanner.vehicleselection." +
                    "VehicleSelectionScreenKt.VehicleSelectionScreenPreview.Dark_en_NIGHT_PIXEL_7_PRO0.png",
            ),
        )
    }

    @Test
    fun `keeps names without a package prefix`() {
        assertEquals("FooPreview.Dark_PIXEL_7.png", shortGoldenName("FooPreview.Dark_PIXEL_7.png"))
    }

    @Test
    fun `does not strip when only the extension would remain`() {
        assertEquals("com.example.Foo.png", shortGoldenName("com.example.Foo.png"))
    }

    @Test
    fun `leaves underscore-style names unchanged`() {
        assertEquals("com.example_MyTest_shot.png", shortGoldenName("com.example_MyTest_shot.png"))
    }
}
