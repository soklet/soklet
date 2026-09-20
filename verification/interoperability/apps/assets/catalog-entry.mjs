import { App } from '@modelcontextprotocol/ext-apps';
import { mountCatalogShell } from './catalog-shell.mjs';

// The build includes the reviewed, pinned SDK; no browser-side package/CDN load.
const app = new App({ name: 'Soklet catalog fixture', version: '1.0.0' }, {});
mountCatalogShell({ app, document });
