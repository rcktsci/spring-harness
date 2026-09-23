import js from '@eslint/js';
import pluginVue from 'eslint-plugin-vue';
import vueTsEslintConfig from '@vue/eslint-config-typescript';
import stylistic from '@stylistic/eslint-plugin';
import globals from 'globals';

const tsParser = vueTsEslintConfig.parser;

export default [
  {
    ignores: [
      'node_modules/**',
      'out/**',
      'dist/**',
      'coverage/**',
      'pnpm-lock.yaml',
      '*.config.js',
      '*.config.cjs',
      // generated from the frozen openapi.yaml — never hand-edited
      'src/api/generated/**',
    ],
  },

  js.configs.recommended,

  ...pluginVue.configs['flat/recommended'],

  ...vueTsEslintConfig({
    rootDir: import.meta.dirname,
  }),

  {
    files: ['**/*.{ts,vue}'],
    languageOptions: {
      parser: tsParser,
      parserOptions: {
        ecmaVersion: 'latest',
        sourceType: 'module',
      },
      globals: {
        ...globals.browser,
        ...globals.node,
        ...globals.es2022,
      },
    },
    plugins: {
      '@stylistic': stylistic,
    },
    rules: {
      '@stylistic/indent': ['error', 2, { SwitchCase: 1 }],
      '@stylistic/semi': ['error', 'always'],
      '@stylistic/quotes': ['error', 'single', { avoidEscape: true }],
      '@stylistic/comma-dangle': ['error', 'always-multiline'],
      '@stylistic/no-trailing-spaces': 'error',
      '@stylistic/eol-last': 'error',
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      '@typescript-eslint/consistent-type-imports': [
        'error',
        { prefer: 'type-imports', fixStyle: 'inline-type-imports' },
      ],
      'no-console': ['warn', { allow: ['warn', 'error'] }],
      'vue/multi-word-component-names': 'off',
      'vue/component-api-style': ['error', ['script-setup']],
      'vue/html-self-closing': [
        'error',
        { html: { void: 'always', normal: 'always', component: 'always' } },
      ],
    },
  },

  {
    files: ['tests/**/*.ts', '**/*.test.ts', '**/*.spec.ts'],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
      'no-console': 'off',
    },
  },

  {
    files: ['src/renderer/**/*.vue'],
    rules: {
      'vue/html-indent': ['error', 2],
    },
  },
];
