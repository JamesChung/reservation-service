-- KEYS[1] quota hash, KEYS[2] holds hash
-- ARGV: id, owner, scopeType, scopeId, resourcesJson, ttlMs, newToken, payloadVersion
local quotaKey = KEYS[1]
local holdsKey = KEYS[2]
local id = ARGV[1]
local owner = ARGV[2]
local scopeType = ARGV[3]
local scopeId = ARGV[4]
local requested = cjson.decode(ARGV[5])
local ttlMs = tonumber(ARGV[6])
local newToken = ARGV[7]
local currentV = tonumber(ARGV[8])

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

local function resources_equal(a, b)
  local n = 0
  for k, v in pairs(a) do
    n = n + 1
    if tonumber(b[k]) ~= tonumber(v) then
      return false
    end
  end
  local m = 0
  for _ in pairs(b) do
    m = m + 1
  end
  return n == m
end

local existing = redis.call('HGET', holdsKey, id)
if existing then
  local obj = decode_hold(existing)
  if obj and live(obj) then
    if not readable(obj) then
      return 'UNSUPPORTED'
    end
    if resources_equal(obj['resources'], requested) then
      return 'OK ' .. existing
    end
    return 'CONFLICT ' .. existing
  end
end

local function used_map()
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
  return used
end

local function usage_json(quota, used)
  local resources = {}
  if quota then
    for name, limit in pairs(quota) do
      resources[name] = { used = used[name] or 0, limit = tonumber(limit) }
    end
  end
  for name, amount in pairs(used) do
    if resources[name] == nil then
      resources[name] = { used = amount, limit = 0 }
    end
  end
  return { scopeType = scopeType, scopeId = scopeId, resources = resources }
end

local function deny(reason, shortages, quota, used)
  return 'DENIED ' .. cjson.encode({
    reason = reason,
    shortages = shortages,
    usage = usage_json(quota, used)
  })
end

local quotaArr = redis.call('HGETALL', quotaKey)
local quota = {}
local hasQuota = #quotaArr > 0
for i = 1, #quotaArr, 2 do
  quota[quotaArr[i]] = tonumber(quotaArr[i + 1])
end

local used = used_map()

if not hasQuota then
  local shortages = {}
  for name, amount in pairs(requested) do
    table.insert(shortages, { name = name, requested = tonumber(amount), used = used[name] or 0, limit = 0 })
  end
  return deny('NO_QUOTA_CONFIGURED', shortages, nil, used)
end

local missing = {}
for name, amount in pairs(requested) do
  if quota[name] == nil then
    table.insert(missing, { name = name, requested = tonumber(amount), used = used[name] or 0, limit = 0 })
  end
end
if #missing > 0 then
  return deny('NO_QUOTA_CONFIGURED', missing, quota, used)
end

local exceeds = {}
for name, amount in pairs(requested) do
  local req = tonumber(amount)
  local limit = quota[name]
  if req > limit then
    table.insert(exceeds, { name = name, requested = req, used = used[name] or 0, limit = limit })
  end
end
if #exceeds > 0 then
  return deny('REQUEST_EXCEEDS_QUOTA', exceeds, quota, used)
end

local capacity = {}
for name, amount in pairs(requested) do
  local req = tonumber(amount)
  local limit = quota[name]
  local occupancy = used[name] or 0
  if occupancy > (limit - req) then
    table.insert(capacity, { name = name, requested = req, used = occupancy, limit = limit })
  end
end
if #capacity > 0 then
  return deny('INSUFFICIENT_CAPACITY', capacity, quota, used)
end

local payload = {
  v = currentV,
  id = id,
  token = newToken,
  owner = owner,
  scopeType = scopeType,
  scopeId = scopeId,
  resources = requested,
  createdAt = nowMs,
  expiresAt = nowMs + ttlMs
}
redis.call('HSET', holdsKey, id, cjson.encode(payload))
return 'OK ' .. cjson.encode(payload)
