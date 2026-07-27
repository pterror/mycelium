import { defineConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'

export default withMermaid(
  defineConfig({
    title: 'mycelium',
    description:
      'A polyglot runtime on GraalVM — one shared Context for JS, Ruby, Lua, and compiled/compile-to-JS languages, with npm/PyPI/RubyGems import and a JVM-level mod loader',

    base: '/mycelium/',
    srcExclude: ['**/CLAUDE.md'],

    themeConfig: {
      nav: [
        { text: 'Guide', link: '/guide/getting-started' },
        { text: 'Reference', link: '/reference/languages' },
        { text: 'Mods', link: '/mods/' },
        { text: 'Research', link: '/research/java-modding-systems' },
      ],

      sidebar: [
        {
          text: 'Guide',
          items: [
            { text: 'Getting started', link: '/guide/getting-started' },
            { text: 'Architecture', link: '/guide/architecture' },
            { text: 'Examples', link: '/guide/examples' },
          ],
        },
        {
          text: 'Reference',
          items: [
            { text: 'Language support', link: '/reference/languages' },
            { text: 'Bun compatibility', link: '/reference/bun-compat' },
          ],
        },
        {
          text: 'Mods',
          items: [
            { text: 'Overview', link: '/mods/' },
            { text: 'Mod authoring guide', link: '/mod-authoring' },
          ],
        },
        {
          text: 'Research',
          items: [
            { text: 'Java modding systems survey', link: '/research/java-modding-systems' },
            { text: 'Mixin evaluation', link: '/research/mixin-evaluation' },
            { text: 'Truffle mod-loader feasibility', link: '/research/truffle-mod-loader-feasibility' },
          ],
        },
      ],

      socialLinks: [
        { icon: 'github', link: 'https://github.com/pterror/mycelium' },
      ],

      search: {
        provider: 'local',
      },

      editLink: {
        pattern: 'https://github.com/pterror/mycelium/edit/master/docs/:path',
        text: 'Edit this page on GitHub',
      },
    },

    vite: {
      optimizeDeps: {
        include: ['mermaid'],
      },
    },
  }),
)
