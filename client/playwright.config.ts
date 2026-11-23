import { defineConfig, devices } from '@playwright/test';

// The tests start the real Java server on its own port, serving the built
// client (client/dist) and a fresh copy of the compiler's examples/ in
// build/e2e-workspace (ant e2e-server), since tests edit files.
// Build first: `ant build`, or run everything with `ant e2e`.
const port = 8091;

export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  reporter: 'list',
  use: {
    baseURL: `http://localhost:${port}`,
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `ant -q -f ../build.xml e2e-server -Dport=${port}`,
    url: `http://localhost:${port}/`,
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
