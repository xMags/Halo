/** The medal's metal: one per tier, so the finish alone says how good the picture is. */
export type MedalFinish = 'gold' | 'silver' | 'bronze' | 'plain'

/**
 * A quality badge as a medal: the resolution on the dark plate, and a tag
 * beside it naming the dynamic range or, without one, the tier. Below HD there
 * is no tag at all, so the plate fills the badge instead of leaving an empty
 * box. The phone stamps the same three fields.
 */
export interface Medal {
  plate: string
  tag: string | null
  finish: MedalFinish
}
