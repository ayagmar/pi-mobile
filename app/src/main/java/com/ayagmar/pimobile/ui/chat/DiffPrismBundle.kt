package com.ayagmar.pimobile.ui.chat

import io.noties.prism4j.annotations.PrismBundle

@PrismBundle(
    include = [
        "kotlin",
        "java",
        "javascript",
        "json",
        "markdown",
        "markup",
        "makefile",
        "python",
        "go",
        "swift",
        "c",
        "cpp",
        "csharp",
        "css",
        "sql",
        "yaml",
    ],
    grammarLocatorClassName = ".DiffPrism4jGrammarLocator",
)
class DiffPrismBundle
