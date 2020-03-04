/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.bot;

import baritone.api.BaritoneAPI;
import baritone.api.bot.IBaritoneUser;
import baritone.api.bot.IUserManager;
import baritone.api.bot.connect.IConnectionResult;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.utils.Helper;
import baritone.bot.connect.ConnectionResult;
import baritone.bot.handler.BotNetHandlerLoginClient;
import net.minecraft.client.multiplayer.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ThreadLanServerPing;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.login.client.CPacketLoginStart;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.Session;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static baritone.api.bot.connect.ConnectionStatus.*;

/**
 * @author Brady
 * @since 11/6/2018
 */
public final class UserManager implements IUserManager, Helper {

    public static final UserManager INSTANCE = new UserManager();

    private final List<IBaritoneUser> users = new CopyOnWriteArrayList<>();

    private final BotWorldProvider worldProvider;

    private UserManager() {
        // Setup an event listener that automatically disconnects bots when we're not in-game
        BaritoneAPI.getProvider().getPrimaryBaritone().getGameEventHandler().registerEventListener(new AbstractGameEventListener() {

            @Override
            public final void onTick(TickEvent event) {
                if (event.getState() == EventState.PRE) {
                    if (event.getType() == TickEvent.Type.OUT) {
                        UserManager.this.users.forEach(UserManager.this::disconnect);
                    }

                    UserManager.this.users.forEach(user -> {
                        if (!user.getNetworkManager().isChannelOpen()) {
                            UserManager.this.disconnect(user);
                        }
                    });

                    UserManager.this.worldProvider.tick();
                }
            }
        });

        this.worldProvider = new BotWorldProvider();
    }

    /**
     * Connects a new user with the specified {@link Session} to the current server.
     *
     * @param session The user session
     * @return The result of the attempted connection
     */
    @Override
    public final IConnectionResult connect(Session session) {
        if (mc.getIntegratedServer() != null && mc.getIntegratedServer().getPublic()) {
            try {
                Field fLanServerPing = IntegratedServer.class.getDeclaredField("lanServerPing");
                fLanServerPing.setAccessible(true);
                ThreadLanServerPing lanServerPing = (ThreadLanServerPing) fLanServerPing.get(mc.getIntegratedServer());

                Field fAddress = lanServerPing.getClass().getDeclaredField("address");
                fAddress.setAccessible(true);
                int port = Integer.parseInt(fAddress.get(lanServerPing).toString());

                // Connect to the server from the parsed server data
                return connect0(session, ServerAddress.fromString("localhost:" + port));
            } catch (Exception e) {
                e.printStackTrace();
                return ConnectionResult.failed(CANT_RESOLVE_HOST);
            }
        }

        ServerData data = mc.getCurrentServerData();
        if (data == null) {
            return ConnectionResult.failed(NO_CURRENT_CONNECTION);
        }

        // Connect to the server from the parsed server data
        return connect0(session, ServerAddress.fromString(data.serverIP));
    }

    /**
     * Connects a new user with the specified {@link Session} to the specified server.
     * <p>
     * Hi Mickey :)
     *
     * @param session The user session
     * @param address The address of the server to connect to
     * @return The result of the attempted connection
     */
    private IConnectionResult connect0(Session session, ServerAddress address) {
        InetAddress inetAddress;

        try {
            inetAddress = InetAddress.getByName(address.getIP());
        } catch (UnknownHostException e) {
            return ConnectionResult.failed(CANT_RESOLVE_HOST);
        }

        try {
            // Initialize Connection
            NetworkManager networkManager = NetworkManager.createNetworkManagerAndConnect(
                    inetAddress,
                    address.getPort(),
                    mc.gameSettings.isUsingNativeTransport()
            );

            // Create User
            BaritoneUser user = new BaritoneUser(this, networkManager, session);
            this.users.add(user);

            // Setup login handler and send connection packets
            networkManager.setNetHandler(new BotNetHandlerLoginClient(networkManager, user));
            networkManager.sendPacket(new C00Handshake(address.getIP(), address.getPort(), EnumConnectionState.LOGIN));
            networkManager.sendPacket(new CPacketLoginStart(session.getProfile()));

            return ConnectionResult.success(user);
        } catch (Exception e) {
            return ConnectionResult.failed(CONNECTION_FAILED);
        }
    }

    /**
     * Notifies the manager of an {@link IBaritoneUser} disconnect, and
     * removes the {@link IBaritoneUser} from the list of users.
     *
     * @param user  The user that disconnected
     * @param state The connection state at the time of disconnect
     */
    public final void notifyDisconnect(IBaritoneUser user, EnumConnectionState state) {
        this.users.remove(user);
    }

    /**
     * @return The bot world provider
     */
    public final BotWorldProvider getWorldProvider() {
        return this.worldProvider;
    }

    @Override
    public final void disconnect(IBaritoneUser user) {
        if (this.users.contains(user)) {
            if (user.getNetworkManager().isChannelOpen()) {
                // It's probably fine to pass null to this, because the handlers aren't doing anything with it
                // noinspection ConstantConditions
                user.getNetworkManager().closeChannel(null);
            }
            this.users.remove(user);
        }
    }

    @Override
    public final List<IBaritoneUser> getUsers() {
        return Collections.unmodifiableList(this.users);
    }
}