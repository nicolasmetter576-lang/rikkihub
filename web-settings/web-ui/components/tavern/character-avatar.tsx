import { Avatar, AvatarFallback, AvatarImage } from "~/components/ui/avatar";

/** A character's picture (the card's image), or the first letter of the name when there is none. */
export function CharacterAvatar({
  name,
  avatarUrl,
  size,
  className,
}: {
  name: string;
  avatarUrl?: string | null;
  size?: "default" | "sm" | "lg";
  className?: string;
}) {
  const letter = (name.trim() || "?").slice(0, 1).toUpperCase();
  return (
    <Avatar size={size} className={className}>
      {avatarUrl ? <AvatarImage src={avatarUrl} alt="" /> : null}
      <AvatarFallback>{letter}</AvatarFallback>
    </Avatar>
  );
}
