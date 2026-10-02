import { setupServer } from 'msw/node';

/** MSW in Node for tests; each test installs its handlers with `server.use(...)`. */
export const server = setupServer();
