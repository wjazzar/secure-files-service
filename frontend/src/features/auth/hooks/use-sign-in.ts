import { useMutation, useQueryClient } from '@tanstack/react-query';

import type { Credentials } from '@/lib/auth/auth-adapter';
import { useAuth } from '@/lib/auth/use-auth';

/**
 * Signs in through the authentication adapter. The query cache is cleared
 * first: nothing fetched for a previous user may be shown to the next one.
 */
export function useSignIn({ onSuccess }: { onSuccess: () => void }) {
  const queryClient = useQueryClient();
  const { login } = useAuth();
  return useMutation({
    mutationFn: async (credentials: Credentials) => {
      queryClient.clear();
      await login(credentials);
    },
    onSuccess,
  });
}
