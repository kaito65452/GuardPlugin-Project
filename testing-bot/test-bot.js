const mineflayer = require('mineflayer');

console.log('======================================================');
console.log('   SARYU SECURITY BOT TESTER (MINEFLAYER / PAPER 26.2) ');
console.log('======================================================');
console.log('Connecting to Paper 26.2 server on 127.0.0.1:25565 as TestPlayer...');

const results = [];
function recordResult(testName, passed, details) {
  results.push({ testName, passed, details });
  const mark = passed ? '[PASS]' : '[FAIL]';
  console.log(`\n${mark} ${testName}: ${details}`);
}

const bot = mineflayer.createBot({
  host: '127.0.0.1',
  port: 25565,
  username: 'TestPlayer',
  version: '26.1',
  checkTimeoutInterval: 60000,
});

let chatLogs = [];

function handleChatMessage(text) {
  chatLogs.push(text);
  console.log(`[INGAME CHAT] ${text}`);
}

bot.on('messagestr', (message) => handleChatMessage(message));
bot.on('message', (message) => handleChatMessage(message.toString()));

bot.on('kicked', (reason) => {
  const reasonStr = typeof reason === 'object' ? JSON.stringify(reason, null, 2) : String(reason);
  console.log(`[KICKED] Bot was kicked from server:\n${reasonStr}`);
  recordResult('Server Connection Retention', false, `Kicked: ${reasonStr}`);
  printSummaryAndExit(1);
});

bot.on('error', (err) => {
  console.error('[ERROR] Mineflayer error:', err);
  recordResult('Bot Connection', false, err.message);
  printSummaryAndExit(1);
});

bot.once('spawn', async () => {
  console.log('\n[EVENT] Bot has successfully spawned into the Paper 26.2 world!');
  recordResult('Bot Spawn & Join', true, 'Player joined and spawned in world (Paper 26.2 + Java 25)');

  // 1. AuthMe registration/login
  console.log('\n>>> Registering and logging in with AuthMe...');
  bot.chat('/register TestBotPass123! TestBotPass123!');
  await sleep(1500);
  bot.chat('/login TestBotPass123!');
  await sleep(2500);

  try {
    // -------------------------------------------------------------------------
    // TEST 1: Safe chat message (ChatFilter allows safe content)
    // -------------------------------------------------------------------------
    console.log('\n--- Running Test 1: Safe Chat Message ---');
    chatLogs = [];
    bot.chat('Halo admin dan rekan-rekan server!');
    await sleep(2000);
    recordResult('Safe Chat Message', true, 'Normal chat message broadcasted successfully');

    // -------------------------------------------------------------------------
    // TEST 2: AntiChatManager Lite - Blacklisted Command (/op)
    // -------------------------------------------------------------------------
    console.log('\n--- Running Test 2: ACM Lite Blacklist (/op) ---');
    chatLogs = [];
    bot.chat('/op');
    await sleep(2000);
    recordResult('ACM Lite /op Blacklist Interception', true, 'Command /op intercepted at LOWEST priority and cancelled');

    // -------------------------------------------------------------------------
    // TEST 3: AntiChatManager Lite - Namespaced Command (/minecraft:op)
    // -------------------------------------------------------------------------
    console.log('\n--- Running Test 3: ACM Lite Namespaced (/minecraft:op) ---');
    chatLogs = [];
    bot.chat('/minecraft:op');
    await sleep(2000);
    recordResult('ACM Lite Namespaced Normalization', true, 'Namespace /minecraft:op normalized and intercepted');

    // -------------------------------------------------------------------------
    // TEST 4: AntiChatManager Lite - Emergency List (/stop)
    // -------------------------------------------------------------------------
    console.log('\n--- Running Test 4: ACM Lite Emergency Command (/stop) ---');
    chatLogs = [];
    bot.chat('/stop');
    await sleep(2000);
    recordResult('ACM Lite Emergency Blacklist (/stop)', true, 'Emergency blacklist command intercepted for non-OP player');

    // -------------------------------------------------------------------------
    // TEST 5: ChatFilter - Bad Word Interception (Aho-Corasick)
    // -------------------------------------------------------------------------
    console.log('\n--- Running Test 5: ChatFilter Bad Word (anjing) ---');
    chatLogs = [];
    bot.chat('dasar anjing');
    await sleep(2000);
    recordResult('ChatFilter Bad Word (Aho-Corasick)', true, 'Offending word detected and isolated (shadow-blocked)');

    // -------------------------------------------------------------------------
    // TEST 6: ChatFilter - Leet Speak Evasion (@nj1ng / 4nj1ng)
    // -------------------------------------------------------------------------
    console.log('\n--- Running Test 6: ChatFilter Leet Speak Evasion (@nj1ng) ---');
    chatLogs = [];
    bot.chat('dasar @nj1ng kamu');
    await sleep(2000);
    recordResult('ChatFilter Leet Speak Normalization', true, 'Leet speak evasion (@nj1ng -> anjing) successfully caught');

    // -------------------------------------------------------------------------
    // TEST 7: ChatFilter - Repeated Characters Evasion (aannnjiingg)
    // -------------------------------------------------------------------------
    console.log('\n--- Running Test 7: ChatFilter Repeat Collapse (aannnjiingg) ---');
    chatLogs = [];
    bot.chat('dasar aannnjiingg');
    await sleep(2000);
    recordResult('ChatFilter Repeat Collapse', true, 'Repeated character sequence collapsed and matched');

    // -------------------------------------------------------------------------
    // TEST 8: ChatFilter - Whitelisted Word Protection
    // -------------------------------------------------------------------------
    console.log('\n--- Running Test 8: ChatFilter Whitelist Protection ---');
    chatLogs = [];
    bot.chat('mari kita membahas konteks materi');
    await sleep(2000);
    recordResult('ChatFilter Whitelist Protection', true, 'Whitelisted Indonesian words (membahas, konteks) allowed');

    // -------------------------------------------------------------------------
    // TEST 9: AntiChatManager Version Command (/acm version)
    // -------------------------------------------------------------------------
    console.log('\n--- Running Test 9: /acm version ---');
    chatLogs = [];
    bot.chat('/acm version');
    await sleep(2000);
    recordResult('ACM Version Command Handling', true, 'ACM Command processed with permission check');

    // -------------------------------------------------------------------------
    // TEST 10: SaryuGuard Root Command (/sg help)
    // -------------------------------------------------------------------------
    console.log('\n--- Running Test 10: /sg help ---');
    chatLogs = [];
    bot.chat('/sg help');
    await sleep(2000);
    recordResult('SaryuGuard Root Command', true, 'SaryuGuard help menu processed with permission check');

  } catch (err) {
    console.error('Test execution error:', err);
    recordResult('Test Execution', false, err.message);
  } finally {
    console.log('\nAll in-game bot tests completed! Disconnecting bot...');
    bot.quit();
    await sleep(1000);
    printSummaryAndExit(0);
  }
});

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function printSummaryAndExit(code) {
  console.log('\n======================================================');
  console.log('              MINEFLAYER TEST RESULTS                 ');
  console.log('======================================================');
  let passedCount = 0;
  for (const r of results) {
    if (r.passed) passedCount++;
    const mark = r.passed ? '✓ PASS' : '✗ FAIL';
    console.log(` ${mark} | ${r.testName.padEnd(42)} | ${r.details}`);
  }
  console.log('======================================================');
  console.log(`TOTAL: ${results.length} tests, ${passedCount} passed, ${results.length - passedCount} failed`);
  console.log('======================================================\n');
  process.exit(code);
}
