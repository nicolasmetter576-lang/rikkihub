import * as React from "react";

import { useQuery } from "@tanstack/react-query";

import { charactersApi, charactersQueryKey } from "../lib/characters";

/** The character a conversation is with: what a message needs to show its avatar and name. */
export interface ActiveCharacterInfo {
  id: string;
  name: string;
  avatarUrl: string | null;
}

/** Provided around the message list; empty when the conversation is not with a character. */
export const ActiveCharacterContext = React.createContext<ActiveCharacterInfo | null>(null);

/**
 * The conversation's character, looked up in the character list (the same query the settings page uses, so the
 * list is fetched once). Null while there is no character, or its card has been deleted.
 */
export function useActiveCharacter(characterId: string | null | undefined): ActiveCharacterInfo | null {
  const list = useQuery({
    queryKey: [...charactersQueryKey, "list", ""],
    queryFn: () => charactersApi.list(),
    enabled: Boolean(characterId),
    staleTime: 30_000,
  });
  return React.useMemo(() => {
    if (!characterId) return null;
    const found = list.data?.find((character) => character.id === characterId);
    return found ? { id: found.id, name: found.name, avatarUrl: found.avatarUrl } : null;
  }, [characterId, list.data]);
}
