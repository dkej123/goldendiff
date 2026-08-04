package com.github.dkwasniak.goldendiff.watch

enum class SnapshotLoadState {
    UNCONFIGURED,
    INITIAL_INDEXING,
    REFRESHING,
    READY,
    EMPTY,
    ERROR,
}
