-- KEYS[1] quota hash, KEYS[2] holds hash
-- ARGV: scopeType, scopeId, payloadVersion
local quotaKey = KEYS[1]
local holdsKey = KEYS[2]
local scopeType = ARGV[1]
local scopeId = ARGV[2]
local currentV = tonumber(ARGV[3])

local t = redis.call('TIME')
local nowMs = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local function decode_hold(raw)
  local ok, obj = pcall(cjson.decode, raw)
  if not ok or type(obj) ~= 'table' then
    return nil
  end
  return obj
end

local function hold_v(obj)
  if obj['v'] == nil then
    return 1
  end
  return tonumber(obj['v'])
end

local function readable(obj)
  local v = hold_v(obj)
  return v ~= nil and v <= currentV
end

local function expired(obj)
  local exp = tonumber(obj['expiresAt'])
  return exp ~= nil and exp <= nowMs
end

local function live(obj)
  local exp = tonumber(obj['expiresAt'])
  return exp ~= nil and exp > nowMs
end

local entries = redis.call('HGETALL', holdsKey)
for i = 1, #entries, 2 do
  local obj = decode_hold(entries[i + 1])
  if obj and readable(obj) and expired(obj) then
    redis.call('HDEL', holdsKey, entries[i])
  end
end

local used = {}
local liveEntries = redis.call('HGETALL', holdsKey)
for i = 1, #liveEntries, 2 do
  local obj = decode_hold(liveEntries[i + 1])
  if obj and live(obj) and obj['resources'] then
    for name, amount in pairs(obj['resources']) do
      local add = tonumber(amount)
      local cur = used[name] or 0
      if cur > 0 and add > 0 and cur > (9223372036854775807 - add) then
        used[name] = 9223372036854775807
      else
        used[name] = cur + add
      end
    end
  end
end

local resources = {}
local quotaArr = redis.call('HGETALL', quotaKey)
for i = 1, #quotaArr, 2 do
  local name = quotaArr[i]
  resources[name] = { used = used[name] or 0, limit = tonumber(quotaArr[i + 1]) }
end
for name, amount in pairs(used) do
  if resources[name] == nil then
    resources[name] = { used = amount, limit = 0 }
  end
end

return 'USAGE ' .. cjson.encode({ scopeType = scopeType, scopeId = scopeId, resources = resources })
