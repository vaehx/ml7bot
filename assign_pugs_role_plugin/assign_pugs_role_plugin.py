import typing

import discord
from discord.ext import commands

from core import checks
from core.models import (
    PermissionLevel,
    getLogger
)

logger = getLogger(__name__)


class AssignPugsRole(commands.Cog):
    """Easily assign PUGS role."""

    def __init__(self, bot):
        self.bot = bot
        self.pugs_guild = bot.guild


    async def _getPugsRole(self):
        return discord.utils.get(self.pugs_guild.roles, name="PUGS")


    @commands.command()
    @checks.has_permissions(PermissionLevel.ADMINISTRATOR)
    async def givepugsrole(self, ctx, user: discord.User = None):
        if user is None:
            thread = ctx.thread
            if not thread or thread.recipient is None:
                await ctx.send("Not in a valid thread. Give the optional user argument to use this command outside of a thread.")
                return
            else:
                user = thread.recipient

        pugs_role = await self._getPugsRole()
        if not pugs_role:
            await ctx.send(f"Error: 'PUGS' role does not exist")
            return

        pugger = self.pugs_guild.get_member(user.id)
        if pugger is None:
            await ctx.send("User not found")
            return

        if pugs_role in pugger.roles:
            await ctx.send(f"User already has the PUGS role")
            return

        await pugger.add_roles(pugs_role)
        await ctx.send(f"PUGs role assigned to user {pugger.mention} ({pugger.id}) successfully")

    @commands.command()
    @checks.has_permissions(PermissionLevel.ADMINISTRATOR)
    async def removepugsrole(self, ctx, user: discord.User = None):
        if user is None:
            thread = ctx.thread
            if not thread or thread.recipient is None:
                await ctx.send("Not in a valid thread. Give the optional user argument to use this command outside of a thread.")
                return
            else:
                user = thread.recipient

        pugs_role = await self._getPugsRole()
        if not pugs_role:
            await ctx.send(f"Error: 'PUGS' role does not exist")
            return

        pugger = self.pugs_guild.get_member(user.id)
        if pugger is None:
            await ctx.send("User not found")
            return

        if not pugs_role in pugger.roles:
            await ctx.send(f"User does not have the PUGS role")
            return

        await pugger.remove_roles(pugs_role)
        await ctx.send(f"PUGs role removed from user {pugger.mention} ({pugger.id}) successfully")


def setup(bot):
    bot.add_cog(AssignPugsRole(bot))
