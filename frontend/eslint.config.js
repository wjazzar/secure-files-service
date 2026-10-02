import js from '@eslint/js';
import importX from 'eslint-plugin-import-x';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import globals from 'globals';
import tseslint from 'typescript-eslint';

/**
 * Import boundaries (ARCHITECTURE.md §3.3), one-way:
 *   app → features → components / hooks / i18n → api → lib / config
 * A feature never imports another feature; they are composed in app/routes.
 */
const FEATURES = ['upload', 'files', 'auth'];

const boundaries = [
  // Features do not import each other.
  ...FEATURES.map((feature) => ({
    target: `./src/features/${feature}`,
    from: './src/features',
    except: [`./${feature}`],
    message: 'A feature must not import another feature: compose them in src/app.',
  })),
  // Nothing below app imports app.
  // (src/testing may: tests render the real providers.)
  { target: './src/!(app|testing)/**', from: './src/app', message: 'Only src/app may import src/app.' },
  // Shared layers do not import features.
  {
    target: ['./src/components', './src/hooks', './src/i18n', './src/api', './src/lib', './src/config'],
    from: './src/features',
    message: 'Shared code must not depend on a feature.',
  },
  // The API layer does not know the interface.
  {
    target: ['./src/api', './src/lib', './src/config'],
    from: ['./src/components', './src/hooks', './src/i18n'],
    message: 'The API layer and lib must not depend on UI code.',
  },
  { target: ['./src/lib', './src/config'], from: './src/api', message: 'lib and config sit below the API layer.' },
];

export default tseslint.config(
  { ignores: ['dist', 'coverage', 'public/mockServiceWorker.js', 'src/components/ui'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.strictTypeChecked, ...tseslint.configs.stylisticTypeChecked],
    languageOptions: {
      ecmaVersion: 2023,
      globals: globals.browser,
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
      'jsx-a11y': jsxA11y,
      'import-x': importX,
    },
    settings: {
      'import-x/resolver': { typescript: { project: './tsconfig.app.json' } },
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      ...jsxA11y.flatConfigs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],

      'import-x/no-restricted-paths': ['error', { zones: boundaries }],
      'import-x/no-cycle': 'error',
      'import-x/no-duplicates': 'error',

      'no-restricted-imports': [
        'error',
        {
          paths: [{ name: 'axios', message: 'Only src/api talks HTTP (rule F-14).' }],
        },
      ],
      'no-restricted-syntax': [
        'error',
        {
          selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']",
          message: 'File names and server data are rendered as plain text only (rule F-9).',
        },
      ],

      '@typescript-eslint/consistent-type-definitions': 'off',
      '@typescript-eslint/restrict-template-expressions': ['error', { allowNumber: true }],
      '@typescript-eslint/no-confusing-void-expression': ['error', { ignoreArrowShorthand: true }],
      '@typescript-eslint/no-misused-promises': ['error', { checksVoidReturn: { attributes: false } }],
    },
  },
  {
    files: ['src/api/**/*.{ts,tsx}'],
    rules: { 'no-restricted-imports': 'off' },
  },
  {
    files: ['**/*.test.{ts,tsx}', 'src/testing/**'],
    languageOptions: { globals: { ...globals.browser, ...globals.node, ...globals.vitest } },
    rules: {
      '@typescript-eslint/no-non-null-assertion': 'off',
      '@typescript-eslint/no-unsafe-assignment': 'off',
      '@typescript-eslint/unbound-method': 'off',
    },
  },
  {
    files: ['*.config.{js,ts}', 'vite.config.ts'],
    languageOptions: { globals: globals.node },
    extends: [tseslint.configs.disableTypeChecked],
  },
);
