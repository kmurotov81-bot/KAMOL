package com.soccer.game

import com.badlogic.gdx.Application
import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.physics.box2d.Box2D

class SoccerGame(private val debug: Boolean = false) : Game() {
    override fun create() {
        Gdx.app.logLevel = if (debug) Application.LOG_DEBUG else Application.LOG_NONE
        Box2D.init()
        soccer.shared.SLog.i("App", "game created (debug=$debug)")
        setScreen(MenuScreen(this))
    }
}
