import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
    testTimeout: 60_000,
    hookTimeout: 180_000,
    // The contract suite boots a real actual-server; run files one at a time.
    fileParallelism: false,
    // Actual's engine and server change behaviour under NODE_ENV=test (in-memory
    // shortcuts, separate migration state). Exercise the real production paths.
    env: { NODE_ENV: 'production' },
  },
});
