-- KEYS[1] holds hash
-- ARGV: payloadVersion
local holdsKey = KEYS[1]
local currentV = tonumber(ARGV[1])

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

local entries = redis.call('HGETALL', holdsKey)
local live = {}
for i = 1, #entries, 2 do
  local obj = decode_hold(entries[i + 1])
  if obj and readable(obj) then
    if expired(obj) then
      redis.call('HDEL', holdsKey, entries[i])
    else
      table.insert(live, obj)
    end
  end
end
if #live == 0 then
  return 'LIST []'
end
return 'LIST ' .. cjson.encode(live)
