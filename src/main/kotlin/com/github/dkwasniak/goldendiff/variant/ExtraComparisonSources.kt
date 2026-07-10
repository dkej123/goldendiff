package com.github.dkwasniak.goldendiff.variant

import java.util.ServiceLoader

object ExtraComparisonSources {
    val all: List<ExtraComparisonSource> by lazy {
        ServiceLoader.load(ExtraComparisonSource::class.java, ExtraComparisonSource::class.java.classLoader)
            .iterator()
            .asSequence()
            .toList()
    }
}
