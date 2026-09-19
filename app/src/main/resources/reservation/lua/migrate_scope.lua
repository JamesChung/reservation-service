-- KEYS[1] previous quota, KEYS[2] previous holds,
-- KEYS[3] current quota, KEYS[4] current holds
-- Rename previous generation onto current when current is empty.
-- Both generations present is SPLIT (do not merge; occupancy would double-count).
local prevQuota = KEYS[1]
local prevHolds = KEYS[2]
local currQuota = KEYS[3]
local currHolds = KEYS[4]

local function exists(key)
  return redis.call('EXISTS', key) == 1
end

local prev = exists(prevQuota) or exists(prevHolds)
local curr = exists(currQuota) or exists(currHolds)

if prev and curr then
  return 'SPLIT'
end
if curr then
  return 'OK'
end
if not prev then
  return 'NONE'
end
if exists(prevQuota) then
  redis.call('RENAME', prevQuota, currQuota)
end
if exists(prevHolds) then
  redis.call('RENAME', prevHolds, currHolds)
end
return 'OK'
