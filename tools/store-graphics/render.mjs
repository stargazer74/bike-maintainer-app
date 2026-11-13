// Renders the store graphics from graphics.html into fastlane/metadata/android/<locale>/images/ (icon.png,
// featureGraphic.png). Needs Playwright with Chromium and the portal repository checked out next to this one (font).
// Usage: node tools/store-graphics/render.mjs   (see README.md)
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { mkdir } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..', '..');
const font = join(repo, '..', 'bike-maintainer-portal', 'wp-content', 'themes', 'bike-maintainer', 'assets', 'fonts',
  'InterVariable.woff2');
const locales = { de: 'de-DE', en: 'en-US' };

// Serves only the page and the font, on localhost; the page loads nothing else.
const server = createServer(async (request, response) => {
  const path = new URL(request.url, 'http://localhost').pathname;
  if (path === '/inter.woff2') {
    response.writeHead(200, { 'Content-Type': 'font/woff2' }).end(await readFile(font));
  } else {
    response.writeHead(200, { 'Content-Type': 'text/html' }).end(await readFile(join(here, 'graphics.html')));
  }
}).listen(0, '127.0.0.1');
await new Promise((resolve) => server.once('listening', resolve));
const base = `http://127.0.0.1:${server.address().port}/`;

const browser = await chromium.launch();
try {
  for (const [lang, locale] of Object.entries(locales)) {
    const out = join(repo, 'fastlane', 'metadata', 'android', locale, 'images');
    await mkdir(out, { recursive: true });
    for (const [name, query, width, height] of [
      ['icon.png', 'kind=icon', 512, 512],
      ['featureGraphic.png', `kind=feature&lang=${lang}`, 1024, 500],
    ]) {
      const page = await browser.newPage({ viewport: { width, height } });
      await page.goto(`${base}?${query}`);
      await page.evaluate(() => document.fonts.ready);
      await page.screenshot({ path: join(out, name), omitBackground: false });
      await page.close();
      console.log(join(out, name));
    }
  }
} finally {
  await browser.close();
  server.close();
}
