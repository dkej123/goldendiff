package com.github.dkwasniak.goldendiff.naming

/**
 * Shortens a Roborazzi-style golden file name by dropping the leading fully-qualified
 * `package.ClassName.` prefix, keeping the human-relevant tail. For example
 * `com.example.FooScreenKt.FooPreview.Dark_PIXEL_7.png` becomes `FooPreview.Dark_PIXEL_7.png`.
 * The package is the run of leading lowercase dotted segments and the class is the segment that
 * follows it. Names without such a prefix (or with nothing meaningful left after it) are unchanged.
 */
fun shortGoldenName(name: String): String {
    val segments = name.split('.')
    // The class is the first segment that does not start lowercase; everything before it is the package.
    val classIndex = segments.indexOfFirst { it.isNotEmpty() && !it[0].isLowerCase() }
    // Require at least one package segment before the class and at least a name + extension after it,
    // so we never strip a plain `Name.ext` or leave only the extension behind.
    if (classIndex < 1 || segments.size - (classIndex + 1) < 2) return name
    return segments.drop(classIndex + 1).joinToString(".")
}

