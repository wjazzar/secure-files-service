import { useQueryClient } from '@tanstack/react-query';
import { ChevronDownIcon, LogOutIcon } from 'lucide-react';
import { useNavigate } from 'react-router';
import { toast } from 'sonner';

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { labels } from '@/i18n/messages';
import { useAuth } from '@/lib/auth/use-auth';
import { initials } from '@/lib/initials';

/** Signed-in user and sign-out. Hidden while no one is signed in. */
export function UserMenu() {
  const { user, logout } = useAuth();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  if (!user) return null;

  const signOut = async () => {
    try {
      await logout();
    } catch {
      // The session is still open at the service: the user stays, and is told.
      toast.error(labels.signOutFailed);
      return;
    }
    // Nothing fetched for this user may remain visible to the next one.
    queryClient.clear();
    void navigate('/login', { replace: true });
  };

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        className="workspace-user-menu flex items-center rounded-full text-left text-header-foreground transition-colors hover:bg-accent focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
        aria-label={user.name}
      >
        <span
          aria-hidden
          className="flex size-8 items-center justify-center rounded-full bg-accent text-sm font-semibold text-accent-foreground ring-1 ring-border"
        >
          {initials(user.name)}
        </span>
        <span className="hidden leading-tight sm:block">
          <span className="workspace-user-name block font-medium">{user.name}</span>
          <span className="workspace-user-login block text-muted-foreground">{user.username}</span>
        </span>
        <ChevronDownIcon aria-hidden className="workspace-user-chevron size-4 text-muted-foreground" />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-64">
        <div className="px-2 py-2">
          <p className="truncate text-sm font-semibold">{user.name}</p>
          {user.email && <p className="truncate text-xs text-muted-foreground">{user.email}</p>}
        </div>
        <DropdownMenuSeparator />
        <DropdownMenuItem variant="destructive" onClick={() => void signOut()}>
          <LogOutIcon />
          {labels.signOut}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
