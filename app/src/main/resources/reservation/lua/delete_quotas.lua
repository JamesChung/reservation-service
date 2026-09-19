-- KEYS[1] quota hash
redis.call('DEL', KEYS[1])
return 'VOID'
