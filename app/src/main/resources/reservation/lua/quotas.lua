-- KEYS[1] quota hash
local arr = redis.call('HGETALL', KEYS[1])
if #arr == 0 then
  return 'NOT_FOUND'
end
local q = {}
for i = 1, #arr, 2 do
  q[arr[i]] = tonumber(arr[i + 1])
end
return 'QUOTA ' .. cjson.encode(q)
