import { defineConfig } from '@playwright/test'
export default defineConfig({
  testDir:'./tests',fullyParallel:false,workers:1,retries:0,timeout:45000,
  reporter:[['list'],['html',{outputFolder:'../artifacts/playwright-report',open:'never'}]],
  outputDir:'../artifacts/test-results',
  use:{baseURL:'http://127.0.0.1:18181',viewport:{width:1512,height:1100},trace:'retain-on-failure',screenshot:'only-on-failure',launchOptions:{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH||undefined}},
  webServer:{command:'java -jar ../backend/target/reconciliation-center-1.0.0.jar --server.port=18181 --spring.datasource.url=jdbc:h2:mem:e2e',url:'http://127.0.0.1:18181/api/session',reuseExistingServer:false,timeout:60000}
})
