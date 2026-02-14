import js from "@eslint/js";
import globals from "globals";
import tseslint from "typescript-eslint";

export default tseslint.config(
    {
        ignores: ["dist/**", "node_modules/**"],
    },
    js.configs.recommended,
    ...tseslint.configs.recommended,
    {
        files: ["**/*.ts"],
        languageOptions: {
            globals: {
                ...globals.node,
            },
        },
        rules: {
            "@typescript-eslint/consistent-type-imports": "error",
        },
    },
    {
        files: ["test/**/*.ts"],
        languageOptions: {
            globals: {
                ...globals.vitest,
            },
        },
    },
);
