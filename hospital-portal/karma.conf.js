(() => {
  const fs = require('fs');
  const candidates = [
    process.env.CHROME_BIN,
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium-browser',
    '/usr/bin/chromium',
    '/opt/google/chrome/chrome',
  ].filter(Boolean);

  let chosen = null;
  for (const p of candidates) {
    try {
      if (p && fs.existsSync(p)) { chosen = p; break; }
    } catch (_) { /* ignore */ }
  }

  if (!chosen) {
    try {
      const puppeteer = require('puppeteer');
      const exe = puppeteer.executablePath();
      if (exe && fs.existsSync(exe)) chosen = exe;
    } catch (_) { /* ignore */ }
  }

  if (chosen) process.env.CHROME_BIN = chosen;
})();

module.exports = function (config) {
  config.set({
    basePath: '',
    frameworks: ['jasmine', '@angular-devkit/build-angular'],
    client: {
      jasmine: {},
      clearContext: false,
    },
    files: [{ pattern: 'src/test-setup.ts', watched: false }],
    reporters: ['progress', 'kjhtml'],
    coverageReporter: {
      dir: require('path').join(__dirname, './coverage'),
      subdir: '.',
      reporters: [
        { type: 'html' },
        { type: 'text-summary' },
        { type: 'lcovonly', file: 'lcov.info' },
        { type: 'json-summary', file: 'coverage-summary.json' },
      ],
    },
    customLaunchers: {
      ChromeHeadlessNoSandbox: {
        base: 'ChromeHeadless',
        flags: [
          '--no-sandbox',
          '--disable-setuid-sandbox',
          '--disable-dev-shm-usage',
          '--disable-gpu',
          '--disable-software-rasterizer',
          '--mute-audio',
          '--headless=new',
        ],
      },
    },
    browsers: [process.env.CI ? 'ChromeHeadlessNoSandbox' : 'ChromeHeadless'],
    singleRun: true,
    restartOnFileChange: false,
  });
};
