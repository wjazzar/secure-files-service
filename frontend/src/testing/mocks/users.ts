/**
 * Demo accounts of the simulated identity provider. No roles: every user has
 * the same rights on a private file space (contracts/openapi.yaml,
 * "Isolation").
 */
export interface MockUser {
  id: string;
  username: string;
  password: string;
  displayName: string;
  email: string;
}

export const MOCK_USERS: readonly MockUser[] = [
  {
    id: 'u-alice',
    username: 'alice',
    password: 'demo',
    displayName: 'Alice Martin',
    email: 'alice.martin@example.com',
  },
  { id: 'u-bob', username: 'bob', password: 'demo', displayName: 'Bob Durand', email: 'bob.durand@example.com' },
  {
    id: 'u-claire',
    username: 'claire',
    password: 'demo',
    displayName: 'Claire Dubois',
    email: 'claire.dubois@example.com',
  },
];

export function findUserById(id: string): MockUser | undefined {
  return MOCK_USERS.find((user) => user.id === id);
}

export function findUserByUsername(username: string): MockUser | undefined {
  return MOCK_USERS.find((user) => user.username === username.trim().toLowerCase());
}
