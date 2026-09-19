-- KEYS[1] quota hash
-- ARGV[1] JSON object name -> limit
local quotaKey = KEYS[1]
redis.call('DEL', quotaKey)
local q = cjson.decode(ARGV[1])
for name, limit in pairs(q) do
  redis.call('HSET', quotaKey, name, tostring(limit))
end
return 'VOID'
